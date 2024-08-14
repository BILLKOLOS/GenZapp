package org.thoughtcrime.securesms.components.settings.app.chats

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.schedulers.Schedulers
import org.thoughtcrime.securesms.backup.v2.BackupRepository
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.util.BackupUtil
import org.thoughtcrime.securesms.util.ConversationUtil
import org.thoughtcrime.securesms.util.ThrottledDebouncer
import org.thoughtcrime.securesms.util.livedata.Store

class ChatsSettingsViewModel @JvmOverloads constructor(
  private val repository: ChatsSettingsRepository = ChatsSettingsRepository()
) : ViewModel() {

  private val refreshDebouncer = ThrottledDebouncer(500L)

  private val store: Store<ChatsSettingsState> = Store(
    ChatsSettingsState(
      generateLinkPreviews = GenZappStore.settings.isLinkPreviewsEnabled,
      useAddressBook = GenZappStore.settings.isPreferSystemContactPhotos,
      keepMutedChatsArchived = GenZappStore.settings.shouldKeepMutedChatsArchived(),
      useSystemEmoji = GenZappStore.settings.isPreferSystemEmoji,
      enterKeySends = GenZappStore.settings.isEnterKeySends,
      localBackupsEnabled = GenZappStore.settings.isBackupEnabled && BackupUtil.canUserAccessBackupDirectory(AppDependencies.application),
      canAccessRemoteBackupsSettings = GenZappStore.backup.areBackupsEnabled
    )
  )

  val state: LiveData<ChatsSettingsState> = store.stateLiveData

  private val disposable = Single.fromCallable { BackupRepository.canAccessRemoteBackupSettings() }
    .subscribeOn(Schedulers.io())
    .observeOn(AndroidSchedulers.mainThread())
    .subscribeBy { canAccessRemoteBackupSettings ->
      store.update { it.copy(canAccessRemoteBackupsSettings = canAccessRemoteBackupSettings) }
    }

  override fun onCleared() {
    disposable.dispose()
  }

  fun setGenerateLinkPreviewsEnabled(enabled: Boolean) {
    store.update { it.copy(generateLinkPreviews = enabled) }
    GenZappStore.settings.isLinkPreviewsEnabled = enabled
    repository.syncLinkPreviewsState()
  }

  fun setUseAddressBook(enabled: Boolean) {
    store.update { it.copy(useAddressBook = enabled) }
    refreshDebouncer.publish { ConversationUtil.refreshRecipientShortcuts() }
    GenZappStore.settings.isPreferSystemContactPhotos = enabled
    repository.syncPreferSystemContactPhotos()
  }

  fun setKeepMutedChatsArchived(enabled: Boolean) {
    store.update { it.copy(keepMutedChatsArchived = enabled) }
    GenZappStore.settings.setKeepMutedChatsArchived(enabled)
    repository.syncKeepMutedChatsArchivedState()
  }

  fun setUseSystemEmoji(enabled: Boolean) {
    store.update { it.copy(useSystemEmoji = enabled) }
    GenZappStore.settings.isPreferSystemEmoji = enabled
  }

  fun setEnterKeySends(enabled: Boolean) {
    store.update { it.copy(enterKeySends = enabled) }
    GenZappStore.settings.isEnterKeySends = enabled
  }

  fun refresh() {
    val backupsEnabled = GenZappStore.settings.isBackupEnabled && BackupUtil.canUserAccessBackupDirectory(AppDependencies.application)
    val remoteBackupsEnabled = GenZappStore.backup.areBackupsEnabled

    if (store.state.localBackupsEnabled != backupsEnabled ||
      store.state.canAccessRemoteBackupsSettings != remoteBackupsEnabled
    ) {
      store.update { it.copy(localBackupsEnabled = backupsEnabled, canAccessRemoteBackupsSettings = remoteBackupsEnabled) }
    }
  }
}
