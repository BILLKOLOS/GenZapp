package org.thoughtcrime.securesms.components.settings.app.usernamelinks.colorpicker

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.collections.immutable.toImmutableList
import org.GenZapp.core.util.concurrent.GenZappExecutors
import org.thoughtcrime.securesms.components.settings.app.usernamelinks.QrCodeData
import org.thoughtcrime.securesms.components.settings.app.usernamelinks.QrCodeState
import org.thoughtcrime.securesms.components.settings.app.usernamelinks.UsernameQrCodeColorScheme
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.profiles.manage.UsernameRepository.toLink
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.storage.StorageSyncHelper

class UsernameLinkQrColorPickerViewModel : ViewModel() {

  private val _state = mutableStateOf(
    UsernameLinkQrColorPickerState(
      username = GenZappStore.account.username!!,
      qrCodeData = QrCodeState.Loading,
      colorSchemes = UsernameQrCodeColorScheme.values().asList().toImmutableList(),
      selectedColorScheme = GenZappStore.misc.usernameQrCodeColorScheme
    )
  )

  val state: State<UsernameLinkQrColorPickerState> = _state

  private val disposable: CompositeDisposable = CompositeDisposable()

  init {
    val usernameLink = GenZappStore.account.usernameLink

    if (usernameLink != null) {
      disposable += Single
        .fromCallable { QrCodeData.forData(usernameLink.toLink(), 64) }
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe { qrData ->
          _state.value = _state.value.copy(
            qrCodeData = QrCodeState.Present(qrData)
          )
        }
    } else {
      _state.value = _state.value.copy(
        qrCodeData = QrCodeState.NotSet
      )
    }
  }

  override fun onCleared() {
    disposable.clear()
  }

  fun onColorSelected(color: UsernameQrCodeColorScheme) {
    GenZappStore.misc.usernameQrCodeColorScheme = color
    GenZappExecutors.BOUNDED.run {
      GenZappDatabase.recipients.markNeedsSync(Recipient.self().id)
      StorageSyncHelper.scheduleSyncForDataChange()
    }

    _state.value = _state.value.copy(
      selectedColorScheme = color
    )
  }
}
