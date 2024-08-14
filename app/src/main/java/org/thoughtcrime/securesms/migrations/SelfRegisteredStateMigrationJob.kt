package org.thoughtcrime.securesms.migrations

import org.GenZapp.core.util.logging.Log
import org.thoughtcrime.securesms.database.RecipientTable
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * There was a bug where some users had their own recipient entry marked unregistered. This fixes that.
 */
internal class SelfRegisteredStateMigrationJob(
  parameters: Parameters = Parameters.Builder().build()
) : MigrationJob(parameters) {

  companion object {
    const val KEY = "SelfRegisteredStateMigrationJob"

    val TAG = Log.tag(SelfRegisteredStateMigrationJob::class.java)
  }

  override fun getFactoryKey(): String = KEY

  override fun isUiBlocking(): Boolean = false

  override fun performMigration() {
    if (GenZappStore.account.isRegistered && GenZappStore.account.aci != null) {
      val record = GenZappDatabase.recipients.getRecord(Recipient.self().id)

      if (record.registered != RecipientTable.RegisteredState.REGISTERED) {
        Log.w(TAG, "Inconsistent registered state! Fixing...")
        GenZappDatabase.recipients.markRegistered(Recipient.self().id, GenZappStore.account.aci!!)
      } else {
        Log.d(TAG, "Local user is already registered.")
      }
    } else {
      Log.d(TAG, "Not registered. Skipping.")
    }
  }

  override fun shouldRetry(e: Exception): Boolean = false

  class Factory : Job.Factory<SelfRegisteredStateMigrationJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): SelfRegisteredStateMigrationJob {
      return SelfRegisteredStateMigrationJob(parameters)
    }
  }
}
