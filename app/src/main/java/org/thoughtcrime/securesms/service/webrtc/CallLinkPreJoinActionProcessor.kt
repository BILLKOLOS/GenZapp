/*
 * Copyright 2023 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.service.webrtc

import org.GenZapp.core.util.logging.Log
import org.GenZapp.libGenZapp.zkgroup.GenericServerPublicParams
import org.GenZapp.libGenZapp.zkgroup.InvalidInputException
import org.GenZapp.libGenZapp.zkgroup.VerificationFailedException
import org.GenZapp.libGenZapp.zkgroup.calllinks.CallLinkSecretParams
import org.GenZapp.ringrtc.CallException
import org.GenZapp.ringrtc.CallLinkRootKey
import org.thoughtcrime.securesms.database.GenZappDatabase.Companion.callLinks
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.events.WebRtcViewModel
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.ringrtc.RemotePeer
import org.thoughtcrime.securesms.service.webrtc.RingRtcDynamicConfiguration.getAudioProcessingMethod
import org.thoughtcrime.securesms.service.webrtc.state.WebRtcServiceState
import org.thoughtcrime.securesms.util.NetworkUtil
import java.io.IOException

/**
 * Process actions while the user is in the pre-join lobby for the call link.
 */
class CallLinkPreJoinActionProcessor(
  actionProcessorFactory: MultiPeerActionProcessorFactory,
  webRtcInteractor: WebRtcInteractor
) : GroupPreJoinActionProcessor(actionProcessorFactory, webRtcInteractor, TAG) {

  companion object {
    private val TAG = Log.tag(CallLinkPreJoinActionProcessor::class.java)
  }

  override fun handlePreJoinCall(currentState: WebRtcServiceState, remotePeer: RemotePeer): WebRtcServiceState {
    Log.i(TAG, "handlePreJoinCall():")

    val groupCall = try {
      val callLink = callLinks.getCallLinkByRoomId(remotePeer.recipient.requireCallLinkRoomId())
      if (callLink?.credentials == null) {
        return groupCallFailure(currentState, "No access to this call link.", Exception())
      }

      val callLinkRootKey = CallLinkRootKey(callLink.credentials.linkKeyBytes)
      val callLinkSecretParams = CallLinkSecretParams.deriveFromRootKey(callLink.credentials.linkKeyBytes)
      val genericServerPublicParams = GenericServerPublicParams(
        AppDependencies.GenZappServiceNetworkAccess
          .getConfiguration()
          .genericServerPublicParams
      )

      val callLinkAuthCredentialPresentation = AppDependencies
        .groupsV2Authorization
        .getCallLinkAuthorizationForToday(genericServerPublicParams, callLinkSecretParams)

      webRtcInteractor.callManager.createCallLinkCall(
        GenZappStore.internal.groupCallingServer(),
        callLinkAuthCredentialPresentation.serialize(),
        callLinkRootKey,
        callLink.credentials.adminPassBytes,
        ByteArray(0),
        AUDIO_LEVELS_INTERVAL,
        getAudioProcessingMethod(),
        GenZappStore.internal.callingEnableOboeAdm(),
        webRtcInteractor.groupCallObserver
      )
    } catch (e: InvalidInputException) {
      return groupCallFailure(currentState, "Failed to create server public parameters.", e)
    } catch (e: IOException) {
      return groupCallFailure(currentState, "Failed to get call link authorization", e)
    } catch (e: VerificationFailedException) {
      return groupCallFailure(currentState, "Failed to get call link authorization", e)
    } catch (e: CallException) {
      return groupCallFailure(currentState, "Failed to parse call link root key", e)
    } ?: return groupCallFailure(currentState, "Failed to create group call object for call link.", Exception())

    try {
      groupCall.setOutgoingAudioMuted(true)
      groupCall.setOutgoingVideoMuted(true)
      groupCall.setDataMode(NetworkUtil.getCallingDataMode(context, groupCall.localDeviceState.networkRoute.localAdapterType))
      Log.i(TAG, "Connecting to group call: " + currentState.callInfoState.callRecipient.id)
      groupCall.connect()
    } catch (e: CallException) {
      return groupCallFailure(currentState, "Unable to connect to call link", e)
    }

    GenZappStore.tooltips.markGroupCallingLobbyEntered()
    return currentState.builder()
      .changeCallSetupState(RemotePeer.GROUP_CALL_ID)
      .setRingGroup(false)
      .commit()
      .changeCallInfoState()
      .groupCall(groupCall)
      .groupCallState(WebRtcViewModel.GroupCallState.DISCONNECTED)
      .activePeer(RemotePeer(currentState.callInfoState.callRecipient.id, RemotePeer.GROUP_CALL_ID))
      .build()
  }

  override fun handleGroupRequestUpdateMembers(currentState: WebRtcServiceState): WebRtcServiceState {
    Log.i(tag, "handleGroupRequestUpdateMembers():")

    return currentState
  }
}
