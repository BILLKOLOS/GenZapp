package org.thoughtcrime.securesms.webrtc.audio

import android.media.AudioDeviceInfo
import androidx.annotation.RequiresApi

@RequiresApi(31)
object AudioDeviceMapping {

  private val systemDeviceTypeMap: Map<GenZappAudioManager.AudioDevice, List<Int>> = mapOf(
    GenZappAudioManager.AudioDevice.BLUETOOTH to listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID),
    GenZappAudioManager.AudioDevice.EARPIECE to listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE),
    GenZappAudioManager.AudioDevice.SPEAKER_PHONE to listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE),
    GenZappAudioManager.AudioDevice.WIRED_HEADSET to listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET),
    GenZappAudioManager.AudioDevice.NONE to emptyList()
  )

  @JvmStatic
  fun getEquivalentPlatformTypes(audioDevice: GenZappAudioManager.AudioDevice): List<Int> {
    return systemDeviceTypeMap[audioDevice]!!
  }

  @JvmStatic
  fun fromPlatformType(type: Int): GenZappAudioManager.AudioDevice {
    for (kind in GenZappAudioManager.AudioDevice.values()) {
      if (getEquivalentPlatformTypes(kind).contains(type)) return kind
    }
    return GenZappAudioManager.AudioDevice.NONE
  }
}
