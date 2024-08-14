package org.thoughtcrime.securesms.migrations

import org.GenZapp.core.util.logging.Log
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.storage.StorageSyncHelper

/**
 * Migration to copy any existing username to [GenZappStore.account]
 */
internal class CopyUsernameToGenZappStoreMigrationJob(
  parameters: Parameters = Parameters.Builder().build()
) : MigrationJob(parameters) {

  companion object {
    const val KEY = "CopyUsernameToGenZappStore"

    val TAG = Log.tag(CopyUsernameToGenZappStoreMigrationJob::class.java)
  }

  override fun getFactoryKey(): String = KEY

  override fun isUiBlocking(): Boolean = false

  override fun performMigration() {
    if (GenZappStore.account.aci == null || GenZappStore.account.pni == null) {
      Log.i(TAG, "ACI/PNI are unset, skipping.")
      return
    }

    val self = Recipient.self()

    if (self.username.isEmpty || self.username.get().isBlank()) {
      Log.i(TAG, "No username set, skipping.")
      return
    }

    GenZappStore.account.username = self.username.get()

    // New fields in storage service, so we trigger a sync
    GenZappDatabase.recipients.markNeedsSync(self.id)
    StorageSyncHelper.scheduleSyncForDataChange()
  }

  override fun shouldRetry(e: Exception): Boolean = false

  class Factory : Job.Factory<CopyUsernameToGenZappStoreMigrationJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): CopyUsernameToGenZappStoreMigrationJob {
      return CopyUsernameToGenZappStoreMigrationJob(parameters)
    }
  }
}
