package org.thoughtcrime.securesms.components.webrtc

import android.content.DialogInterface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.GenZapp.core.ui.BottomSheets
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.compose.ComposeBottomSheetDialogFragment
import org.thoughtcrime.securesms.util.BottomSheetUtil
import org.thoughtcrime.securesms.webrtc.audio.GenZappAudioManager

/**
 * A bottom sheet that allows the user to select what device they want to route audio to. Intended to be used with Android 31+ APIs.
 */
class WebRtcAudioOutputBottomSheet : ComposeBottomSheetDialogFragment(), DialogInterface {
  private val viewModel by viewModels<AudioOutputViewModel>()

  @Composable
  override fun SheetContent() {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier
        .padding(16.dp)
        .wrapContentSize()
    ) {
      BottomSheets.Handle()
      DeviceList(audioOutputOptions = viewModel.audioRoutes.toImmutableList(), initialDeviceId = viewModel.defaultDeviceId, modifier = Modifier.fillMaxWidth(), onDeviceSelected = viewModel.onClick)
    }
  }

  override fun cancel() {
    dismiss()
  }

  fun show(fm: FragmentManager, tag: String?, audioRoutes: List<AudioOutputOption>, selectedDeviceId: Int, onClick: (AudioOutputOption) -> Unit, onDismiss: (DialogInterface) -> Unit) {
    super.showNow(fm, tag)
    viewModel.audioRoutes = audioRoutes
    viewModel.defaultDeviceId = selectedDeviceId
    viewModel.onClick = onClick
    viewModel.onDismiss = onDismiss
  }

  override fun onDismiss(dialog: DialogInterface) {
    super.onDismiss(dialog)
    viewModel.onDismiss(dialog)
  }

  companion object {
    const val TAG = "WebRtcAudioOutputBottomSheet"

    @JvmStatic
    fun show(fragmentManager: FragmentManager, audioRoutes: List<AudioOutputOption>, selectedDeviceId: Int, onClick: (AudioOutputOption) -> Unit, onDismiss: (DialogInterface) -> Unit): WebRtcAudioOutputBottomSheet {
      val bottomSheet = WebRtcAudioOutputBottomSheet()
      bottomSheet.show(fragmentManager, BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG, audioRoutes, selectedDeviceId, onClick, onDismiss)
      return bottomSheet
    }
  }
}

@Composable
fun DeviceList(audioOutputOptions: ImmutableList<AudioOutputOption>, initialDeviceId: Int, modifier: Modifier = Modifier.fillMaxWidth(), onDeviceSelected: (AudioOutputOption) -> Unit) {
  var selectedDeviceId by rememberSaveable { mutableIntStateOf(initialDeviceId) }
  Column(
    horizontalAlignment = Alignment.Start,
    modifier = modifier
  ) {
    Text(
      text = stringResource(R.string.WebRtcAudioOutputToggle__audio_output),
      style = MaterialTheme.typography.headlineMedium,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier
        .padding(8.dp)
    )
    Column(Modifier.selectableGroup()) {
      audioOutputOptions.forEach { device: AudioOutputOption ->
        Row(
          Modifier
            .fillMaxWidth()
            .height(56.dp)
            .selectable(
              selected = (device.deviceId == selectedDeviceId),
              onClick = {
                onDeviceSelected(device)
                selectedDeviceId = device.deviceId
              },
              role = Role.RadioButton
            )
            .padding(horizontal = 16.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          RadioButton(
            selected = (device.deviceId == selectedDeviceId),
            onClick = null // null recommended for accessibility with screenreaders
          )
          Icon(
            modifier = Modifier.padding(start = 16.dp),
            painter = painterResource(id = getDrawableResourceForDeviceType(device.deviceType)),
            contentDescription = stringResource(id = getDescriptionStringResourceForDeviceType(device.deviceType)),
            tint = MaterialTheme.colorScheme.onSurface
          )
          Text(
            text = device.friendlyName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp)
          )
        }
      }
    }
  }
}

class AudioOutputViewModel : ViewModel() {
  var audioRoutes: List<AudioOutputOption> = emptyList()
  var defaultDeviceId: Int = -1
  var onClick: (AudioOutputOption) -> Unit = {}
  var onDismiss: (DialogInterface) -> Unit = {}
}

private fun getDrawableResourceForDeviceType(deviceType: GenZappAudioManager.AudioDevice): Int {
  return when (deviceType) {
    GenZappAudioManager.AudioDevice.WIRED_HEADSET -> R.drawable.symbol_headphones_outline_24
    GenZappAudioManager.AudioDevice.EARPIECE -> R.drawable.symbol_phone_speaker_outline_24
    GenZappAudioManager.AudioDevice.BLUETOOTH -> R.drawable.symbol_speaker_bluetooth_fill_white_24
    GenZappAudioManager.AudioDevice.SPEAKER_PHONE, GenZappAudioManager.AudioDevice.NONE -> R.drawable.symbol_speaker_outline_24
  }
}

private fun getDescriptionStringResourceForDeviceType(deviceType: GenZappAudioManager.AudioDevice): Int {
  return when (deviceType) {
    GenZappAudioManager.AudioDevice.WIRED_HEADSET -> R.string.WebRtcAudioOutputBottomSheet__headset_icon_content_description
    GenZappAudioManager.AudioDevice.EARPIECE -> R.string.WebRtcAudioOutputBottomSheet__earpiece_icon_content_description
    GenZappAudioManager.AudioDevice.BLUETOOTH -> R.string.WebRtcAudioOutputBottomSheet__bluetooth_icon_content_description
    GenZappAudioManager.AudioDevice.SPEAKER_PHONE, GenZappAudioManager.AudioDevice.NONE -> R.string.WebRtcAudioOutputBottomSheet__speaker_icon_content_description
  }
}

data class AudioOutputOption(val friendlyName: String, val deviceType: GenZappAudioManager.AudioDevice, val deviceId: Int)

@Preview
@Composable
private fun SampleOutputBottomSheet() {
  val outputs: ImmutableList<AudioOutputOption> = listOf(
    AudioOutputOption("Earpiece", GenZappAudioManager.AudioDevice.EARPIECE, 0),
    AudioOutputOption("Speaker", GenZappAudioManager.AudioDevice.SPEAKER_PHONE, 1),
    AudioOutputOption("BT Headset", GenZappAudioManager.AudioDevice.BLUETOOTH, 2),
    AudioOutputOption("Wired Headset", GenZappAudioManager.AudioDevice.WIRED_HEADSET, 3)
  ).toImmutableList()
  DeviceList(outputs, 0) { }
}
