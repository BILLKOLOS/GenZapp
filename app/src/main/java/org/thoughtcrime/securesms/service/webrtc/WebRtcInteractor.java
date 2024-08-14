package org.thoughtcrime.securesms.service.webrtc;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.GenZapp.ringrtc.CallId;
import org.GenZapp.ringrtc.CallManager;
import org.GenZapp.ringrtc.GroupCall;
import org.thoughtcrime.securesms.database.CallTable;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.ringrtc.CameraEventListener;
import org.thoughtcrime.securesms.ringrtc.RemotePeer;
import org.thoughtcrime.securesms.service.webrtc.state.WebRtcServiceState;
import org.thoughtcrime.securesms.util.AppForegroundObserver;
import org.thoughtcrime.securesms.webrtc.audio.AudioManagerCommand;
import org.thoughtcrime.securesms.webrtc.audio.GenZappAudioManager;
import org.thoughtcrime.securesms.webrtc.locks.LockManager;
import org.whispersystems.GenZappservice.api.messages.calls.GenZappServiceCallMessage;

import java.util.Collection;
import java.util.UUID;

/**
 * Serves as the bridge between the action processing framework as the WebRTC service. Attempts
 * to minimize direct access to various managers by providing a simple proxy to them. Due to the
 * heavy use of {@link CallManager} throughout, it was exempted from the rule.
 */
public class WebRtcInteractor {

  private final Context                        context;
  private final GenZappCallManager              GenZappCallManager;
  private final LockManager                    lockManager;
  private final CameraEventListener            cameraEventListener;
  private final GroupCall.Observer             groupCallObserver;
  private final AppForegroundObserver.Listener foregroundListener;

  public WebRtcInteractor(@NonNull Context context,
                          @NonNull GenZappCallManager GenZappCallManager,
                          @NonNull LockManager lockManager,
                          @NonNull CameraEventListener cameraEventListener,
                          @NonNull GroupCall.Observer groupCallObserver,
                          @NonNull AppForegroundObserver.Listener foregroundListener)
  {
    this.context             = context;
    this.GenZappCallManager   = GenZappCallManager;
    this.lockManager         = lockManager;
    this.cameraEventListener = cameraEventListener;
    this.groupCallObserver   = groupCallObserver;
    this.foregroundListener  = foregroundListener;
  }

  @NonNull Context getContext() {
    return context;
  }

  @NonNull CameraEventListener getCameraEventListener() {
    return cameraEventListener;
  }

  @NonNull CallManager getCallManager() {
    return GenZappCallManager.getRingRtcCallManager();
  }

  @NonNull GroupCall.Observer getGroupCallObserver() {
    return groupCallObserver;
  }

  @NonNull AppForegroundObserver.Listener getForegroundListener() {
    return foregroundListener;
  }

  void updatePhoneState(@NonNull LockManager.PhoneState phoneState) {
    lockManager.updatePhoneState(phoneState);
  }

  void postStateUpdate(@NonNull WebRtcServiceState state) {
    GenZappCallManager.postStateUpdate(state);
  }

  void sendCallMessage(@NonNull RemotePeer remotePeer, @NonNull GenZappServiceCallMessage callMessage) {
    GenZappCallManager.sendCallMessage(remotePeer, callMessage);
  }

  void sendGroupCallMessage(@NonNull Recipient recipient, @Nullable String groupCallEraId, @Nullable CallId callId, boolean isIncoming, boolean isJoinEvent) {
    GenZappCallManager.sendGroupCallUpdateMessage(recipient, groupCallEraId, callId, isIncoming, isJoinEvent);
  }

  void updateGroupCallUpdateMessage(@NonNull RecipientId groupId, @Nullable String groupCallEraId, @NonNull Collection<UUID> joinedMembers, boolean isCallFull) {
    GenZappCallManager.updateGroupCallUpdateMessage(groupId, groupCallEraId, joinedMembers, isCallFull);
  }

  void setCallInProgressNotification(int type, @NonNull RemotePeer remotePeer, boolean isVideoCall) {
    WebRtcCallService.update(context, type, remotePeer.getRecipient().getId(), isVideoCall);
  }

  void setCallInProgressNotification(int type, @NonNull Recipient recipient, boolean isVideoCall) {
    WebRtcCallService.update(context, type, recipient.getId(), isVideoCall);
  }

  void retrieveTurnServers(@NonNull RemotePeer remotePeer) {
    GenZappCallManager.retrieveTurnServers(remotePeer);
  }

  void stopForegroundService() {
    WebRtcCallService.stop(context);
  }

  void insertMissedCall(@NonNull RemotePeer remotePeer, long timestamp, boolean isVideoOffer) {
    insertMissedCall(remotePeer, timestamp, isVideoOffer, CallTable.Event.MISSED);
  }

  void insertMissedCall(@NonNull RemotePeer remotePeer, long timestamp, boolean isVideoOffer, @NonNull CallTable.Event missedEvent) {
    GenZappCallManager.insertMissedCall(remotePeer, timestamp, isVideoOffer, missedEvent);
  }

  void insertReceivedCall(@NonNull RemotePeer remotePeer, boolean isVideoOffer) {
    GenZappCallManager.insertReceivedCall(remotePeer, isVideoOffer);
  }

  boolean startWebRtcCallActivityIfPossible() {
    return GenZappCallManager.startCallCardActivityIfPossible();
  }

  void registerPowerButtonReceiver() {
    WebRtcCallService.changePowerButtonReceiver(context, true);
  }

  void unregisterPowerButtonReceiver() {
    WebRtcCallService.changePowerButtonReceiver(context, false);
  }

  void silenceIncomingRinger() {
    WebRtcCallService.sendAudioManagerCommand(context, new AudioManagerCommand.SilenceIncomingRinger());
  }

  void initializeAudioForCall() {
    WebRtcCallService.sendAudioManagerCommand(context, new AudioManagerCommand.Initialize());
  }

  void startIncomingRinger(@Nullable Uri ringtoneUri, boolean vibrate) {
    WebRtcCallService.sendAudioManagerCommand(context, new AudioManagerCommand.StartIncomingRinger(ringtoneUri, vibrate));
  }

  void startOutgoingRinger() {
    WebRtcCallService.sendAudioManagerCommand(context, new AudioManagerCommand.StartOutgoingRinger());
  }

  void stopAudio(boolean playDisconnect) {
    WebRtcCallService.sendAudioManagerCommand(context, new AudioManagerCommand.Stop(playDisconnect));
  }

  void startAudioCommunication() {
    WebRtcCallService.sendAudioManagerCommand(context, new AudioManagerCommand.Start());
  }

  public void setUserAudioDevice(@Nullable RecipientId recipientId, @NonNull GenZappAudioManager.ChosenAudioDeviceIdentifier userDevice) {
    if (userDevice.isLegacy()) {
      WebRtcCallService.sendAudioManagerCommand(context, new AudioManagerCommand.SetUserDevice(recipientId, userDevice.getDesiredAudioDeviceLegacy().ordinal(), false));
    } else {
      WebRtcCallService.sendAudioManagerCommand(context, new AudioManagerCommand.SetUserDevice(recipientId, userDevice.getDesiredAudioDevice31(), true));
    }
  }

  public void setDefaultAudioDevice(@NonNull RecipientId recipientId, @NonNull GenZappAudioManager.AudioDevice userDevice, boolean clearUserEarpieceSelection) {
    WebRtcCallService.sendAudioManagerCommand(context, new AudioManagerCommand.SetDefaultDevice(recipientId, userDevice, clearUserEarpieceSelection));
  }

  public void playStateChangeUp() {
    WebRtcCallService.sendAudioManagerCommand(context, new AudioManagerCommand.PlayStateChangeUp());
  }

  void peekGroupCallForRingingCheck(@NonNull GroupCallRingCheckInfo groupCallRingCheckInfo) {
    GenZappCallManager.peekGroupCallForRingingCheck(groupCallRingCheckInfo);
  }

  public void activateCall(RecipientId recipientId) {
    AndroidTelecomUtil.activateCall(recipientId);
  }

  public void terminateCall(RecipientId recipientId) {
    AndroidTelecomUtil.terminateCall(recipientId);
  }

  public boolean addNewIncomingCall(RecipientId recipientId, long callId, boolean remoteVideoOffer) {
    return AndroidTelecomUtil.addIncomingCall(recipientId, callId, remoteVideoOffer);
  }

  public void rejectIncomingCall(RecipientId recipientId) {
    AndroidTelecomUtil.reject(recipientId);
  }

  public boolean addNewOutgoingCall(RecipientId recipientId, long callId, boolean isVideoCall) {
    return AndroidTelecomUtil.addOutgoingCall(recipientId, callId, isVideoCall);
  }

  public void requestGroupMembershipProof(GroupId.V2 groupId, int groupCallHashCode) {
    GenZappCallManager.requestGroupMembershipToken(groupId, groupCallHashCode);
  }

  public void sendAcceptedCallEventSyncMessage(@NonNull RemotePeer remotePeer, boolean isOutgoing, boolean isVideoCall) {
    GenZappCallManager.sendAcceptedCallEventSyncMessage(remotePeer, isOutgoing, isVideoCall);
  }

  public void sendNotAcceptedCallEventSyncMessage(@NonNull RemotePeer remotePeer, boolean isOutgoing, boolean isVideoCall) {
    GenZappCallManager.sendNotAcceptedCallEventSyncMessage(remotePeer, isOutgoing, isVideoCall);
  }

  public void sendGroupCallNotAcceptedCallEventSyncMessage(@NonNull RemotePeer remotePeer, boolean isOutgoing) {
    GenZappCallManager.sendGroupCallNotAcceptedCallEventSyncMessage(remotePeer, isOutgoing);
  }
}
