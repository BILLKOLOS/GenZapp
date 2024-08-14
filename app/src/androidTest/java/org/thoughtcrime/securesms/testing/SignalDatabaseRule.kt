package org.thoughtcrime.securesms.testing

import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.GenZapp.core.util.deleteAll
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.database.ThreadTable
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.whispersystems.GenZappservice.api.push.ServiceId.ACI
import org.whispersystems.GenZappservice.api.push.ServiceId.PNI
import java.util.UUID

/**
 * Sets up bare-minimum to allow writing unit tests against the database,
 * including setting up the local ACI and PNI pair.
 *
 * @param deleteAllThreadsOnEachRun Run deleteAllThreads between each unit test
 */
class GenZappDatabaseRule(
  private val deleteAllThreadsOnEachRun: Boolean = true
) : TestWatcher() {

  val localAci: ACI = ACI.from(UUID.randomUUID())
  val localPni: PNI = PNI.from(UUID.randomUUID())

  override fun starting(description: Description?) {
    deleteAllThreads()

    GenZappStore.account.setAci(localAci)
    GenZappStore.account.setPni(localPni)
  }

  override fun finished(description: Description?) {
    deleteAllThreads()
  }

  private fun deleteAllThreads() {
    if (deleteAllThreadsOnEachRun) {
      GenZappDatabase.threads.deleteAllConversations()
      GenZappDatabase.rawDatabase.deleteAll(ThreadTable.TABLE_NAME)
    }
  }
}
