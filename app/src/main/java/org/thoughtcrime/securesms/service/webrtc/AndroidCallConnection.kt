package org.thoughtcrime.securesms.service.webrtc

import android.content.Context
import android.content.Intent
import android.telecom.CallAudioState
import android.telecom.Connection
import androidx.annotation.RequiresApi
import org.GenZapp.core.util.logging.Log
import org.thoughtcrime.securesms.WebRtcCallActivity
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.webrtc.CallNotificationBuilder
import org.thoughtcrime.securesms.webrtc.audio.AudioManagerCommand
import org.thoughtcrime.securesms.webrtc.audio.GenZappAudioManager

/**
 * GenZapp implementation for the telecom system connection. Provides an interaction point for the system to
 * inform us about changes in the telecom system. Created and returned by [AndroidCallConnectionService].
 */
@RequiresApi(26)
class AndroidCallConnection(
  private val context: Context,
  private val recipientId: RecipientId,
  isOutgoing: Boolean,
  private val isVideoCall: Boolean
) : Connection() {

  private var needToResetAudioRoute = isOutgoing && !isVideoCall
  private var initialAudioRoute: GenZappAudioManager.AudioDevice? = null

  init {
    connectionProperties = PROPERTY_SELF_MANAGED
    connectionCapabilities = CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL or
      CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL or
      CAPABILITY_MUTE
  }

  override fun onShowIncomingCallUi() {
    Log.i(TAG, "onShowIncomingCallUi()")
    WebRtcCallService.update(context, CallNotificationBuilder.TYPE_INCOMING_CONNECTING, recipientId, isVideoCall)
    setRinging()
  }

  override fun onCallAudioStateChanged(state: CallAudioState) {
    Log.i(TAG, "onCallAudioStateChanged($state)")

    val activeDevice = state.route.toDevices().firstOrNull() ?: GenZappAudioManager.AudioDevice.EARPIECE
    val availableDevices = state.supportedRouteMask.toDevices()

    AppDependencies.GenZappCallManager.onAudioDeviceChanged(activeDevice, availableDevices)

    if (needToResetAudioRoute) {
      if (initialAudioRoute == null) {
        initialAudioRoute = activeDevice
      } else if (activeDevice == GenZappAudioManager.AudioDevice.SPEAKER_PHONE) {
        Log.i(TAG, "Resetting audio route from SPEAKER_PHONE to $initialAudioRoute")
        AndroidTelecomUtil.selectAudioDevice(recipientId, initialAudioRoute!!)
        needToResetAudioRoute = false
      }
    }
  }

  override fun onAnswer(videoState: Int) {
    Log.i(TAG, "onAnswer($videoState)")
    if (Permissions.hasAll(context, android.Manifest.permission.RECORD_AUDIO)) {
      AppDependencies.GenZappCallManager.acceptCall(false)
    } else {
      val intent = Intent(context, WebRtcCallActivity::class.java)
      intent.action = if (isVideoCall) WebRtcCallActivity.ANSWER_VIDEO_ACTION else WebRtcCallActivity.ANSWER_ACTION
      intent.flags = intent.flags or Intent.FLAG_ACTIVITY_NEW_TASK
      context.startActivity(intent)
    }
  }

  override fun onSilence() {
    WebRtcCallService.sendAudioManagerCommand(context, AudioManagerCommand.SilenceIncomingRinger())
  }

  override fun onReject() {
    Log.i(TAG, "onReject()")
    WebRtcCallService.denyCall(context)
  }

  override fun onDisconnect() {
    Log.i(TAG, "onDisconnect()")
    WebRtcCallService.hangup(context)
  }

  companion object {
    private val TAG: String = Log.tag(AndroidCallConnection::class.java)
  }
}

private fun Int.toDevices(): Set<GenZappAudioManager.AudioDevice> {
  val devices = mutableSetOf<GenZappAudioManager.AudioDevice>()

  if (this and CallAudioState.ROUTE_BLUETOOTH != 0) {
    devices += GenZappAudioManager.AudioDevice.BLUETOOTH
  }

  if (this and CallAudioState.ROUTE_EARPIECE != 0) {
    devices += GenZappAudioManager.AudioDevice.EARPIECE
  }

  if (this and CallAudioState.ROUTE_WIRED_HEADSET != 0) {
    devices += GenZappAudioManager.AudioDevice.WIRED_HEADSET
  }

  if (this and CallAudioState.ROUTE_SPEAKER != 0) {
    devices += GenZappAudioManager.AudioDevice.SPEAKER_PHONE
  }

  return devices
}
