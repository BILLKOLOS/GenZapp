package org.thoughtcrime.securesms.components.settings.app.account

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.thoughtcrime.securesms.util.livedata.Store

class AccountSettingsViewModel : ViewModel() {
  private val store: Store<AccountSettingsState> = Store(getCurrentState())

  val state: LiveData<AccountSettingsState> = store.stateLiveData

  fun refreshState() {
    store.update { getCurrentState() }
  }

  private fun getCurrentState(): AccountSettingsState {
    return AccountSettingsState(
      hasPin = GenZappStore.svr.hasPin() && !GenZappStore.svr.hasOptedOut(),
      pinRemindersEnabled = GenZappStore.pin.arePinRemindersEnabled(),
      registrationLockEnabled = GenZappStore.svr.isRegistrationLockEnabled,
      userUnregistered = TextSecurePreferences.isUnauthorizedReceived(AppDependencies.application),
      clientDeprecated = GenZappStore.misc.isClientDeprecated
    )
  }
}
