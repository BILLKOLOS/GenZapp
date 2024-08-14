package org.thoughtcrime.securesms.migrations

import org.GenZapp.core.util.logging.Log
import org.thoughtcrime.securesms.database.IdentityTable
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobs.AccountConsistencyWorkerJob
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Migration to help cleanup some inconsistent state for ourself in the identity table.
 */
internal class IdentityTableCleanupMigrationJob(
  parameters: Parameters = Parameters.Builder().build()
) : MigrationJob(parameters) {

  companion object {
    const val KEY = "IdentityTableCleanupMigrationJob"

    val TAG = Log.tag(IdentityTableCleanupMigrationJob::class.java)
  }

  override fun getFactoryKey(): String = KEY

  override fun isUiBlocking(): Boolean = false

  override fun performMigration() {
    if (GenZappStore.account.aci == null || GenZappStore.account.pni == null) {
      Log.i(TAG, "ACI/PNI are unset, skipping.")
      return
    }

    if (!GenZappStore.account.hasAciIdentityKey()) {
      Log.i(TAG, "No ACI identity set yet, skipping.")
      return
    }

    if (!GenZappStore.account.hasPniIdentityKey()) {
      Log.i(TAG, "No PNI identity set yet, skipping.")
      return
    }

    AppDependencies.protocolStore.aci().identities().saveIdentityWithoutSideEffects(
      Recipient.self().id,
      GenZappStore.account.aci!!,
      GenZappStore.account.aciIdentityKey.publicKey,
      IdentityTable.VerifiedStatus.VERIFIED,
      true,
      System.currentTimeMillis(),
      true
    )

    AppDependencies.protocolStore.pni().identities().saveIdentityWithoutSideEffects(
      Recipient.self().id,
      GenZappStore.account.pni!!,
      GenZappStore.account.pniIdentityKey.publicKey,
      IdentityTable.VerifiedStatus.VERIFIED,
      true,
      System.currentTimeMillis(),
      true
    )

    AppDependencies.jobManager.add(AccountConsistencyWorkerJob())
  }

  override fun shouldRetry(e: Exception): Boolean = false

  class Factory : Job.Factory<IdentityTableCleanupMigrationJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): IdentityTableCleanupMigrationJob {
      return IdentityTableCleanupMigrationJob(parameters)
    }
  }
}
