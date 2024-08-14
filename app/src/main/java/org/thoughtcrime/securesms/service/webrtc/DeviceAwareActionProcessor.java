package org.thoughtcrime.securesms.service.webrtc;

import androidx.annotation.NonNull;

import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.components.webrtc.BroadcastVideoSink;
import org.thoughtcrime.securesms.events.WebRtcViewModel;
import org.thoughtcrime.securesms.ringrtc.CameraState;
import org.thoughtcrime.securesms.ringrtc.RemotePeer;
import org.thoughtcrime.securesms.service.webrtc.state.WebRtcServiceState;
import org.thoughtcrime.securesms.webrtc.audio.GenZappAudioManager;

import java.util.Set;

/**
 * Encapsulates the shared logic to deal with local device actions. Other action processors inherit
 * the behavior by extending it instead of delegating. It is not intended to be the main processor
 * for the system.
 */
public abstract class DeviceAwareActionProcessor extends WebRtcActionProcessor {

  public DeviceAwareActionProcessor(@NonNull WebRtcInteractor webRtcInteractor, @NonNull String tag) {
    super(webRtcInteractor, tag);
  }

  @Override
  protected @NonNull WebRtcServiceState handleAudioDeviceChanged(@NonNull WebRtcServiceState currentState, @NonNull GenZappAudioManager.AudioDevice activeDevice, @NonNull Set<GenZappAudioManager.AudioDevice> availableDevices) {
    Log.i(tag, "handleAudioDeviceChanged(): active: " + activeDevice + " available: " + availableDevices);

    if (!currentState.getLocalDeviceState().getCameraState().isEnabled()) {
      if (currentState.getCallInfoState().getCallState() == WebRtcViewModel.State.CALL_CONNECTED) {
        webRtcInteractor.updatePhoneState(WebRtcUtil.getInCallPhoneState(context));
      } else {
        Log.i(tag, "handleAudioDeviceChanged(): call not connected, not updating phone state");
      }
    }

    return currentState.builder()
                       .changeLocalDeviceState()
                       .setActiveDevice(activeDevice)
                       .setAvailableDevices(availableDevices)
                       .build();
  }

  @Override
  protected @NonNull WebRtcServiceState handleSetUserAudioDevice(@NonNull WebRtcServiceState currentState, @NonNull GenZappAudioManager.ChosenAudioDeviceIdentifier userDevice) {
    Log.i(tag, "handleSetUserAudioDevice(): userDevice: " + userDevice);

    RemotePeer activePeer = currentState.getCallInfoState().getActivePeer();
    webRtcInteractor.setUserAudioDevice(activePeer != null ? activePeer.getId() : null, userDevice);

    return currentState;
  }

  @Override
  protected @NonNull WebRtcServiceState handleSetCameraFlip(@NonNull WebRtcServiceState currentState) {
    Log.i(tag, "handleSetCameraFlip():");

    if (currentState.getLocalDeviceState().getCameraState().isEnabled() && currentState.getVideoState().getCamera() != null) {
      currentState.getVideoState().getCamera().flip();
      return currentState.builder()
                         .changeLocalDeviceState()
                         .cameraState(currentState.getVideoState().getCamera().getCameraState())
                         .build();
    }
    return currentState;
  }

  @Override
  public @NonNull WebRtcServiceState handleCameraSwitchCompleted(@NonNull WebRtcServiceState currentState, @NonNull CameraState newCameraState) {
    Log.i(tag, "handleCameraSwitchCompleted():");

    BroadcastVideoSink localSink = currentState.getVideoState().getLocalSink();
    if (localSink != null) {
      localSink.setRotateToRightSide(newCameraState.getActiveDirection() == CameraState.Direction.BACK);
    }

    return currentState.builder()
                       .changeLocalDeviceState()
                       .cameraState(newCameraState)
                       .build();
  }
}
