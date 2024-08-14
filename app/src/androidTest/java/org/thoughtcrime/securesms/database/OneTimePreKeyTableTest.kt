/*
 * Copyright 2023 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import org.junit.Test
import org.GenZapp.core.util.readToSingleObject
import org.GenZapp.core.util.requireLongOrNull
import org.GenZapp.core.util.select
import org.GenZapp.core.util.update
import org.GenZapp.libGenZapp.protocol.ecc.Curve
import org.GenZapp.libGenZapp.protocol.state.PreKeyRecord
import org.whispersystems.GenZappservice.api.push.ServiceId
import org.whispersystems.GenZappservice.api.push.ServiceId.ACI
import org.whispersystems.GenZappservice.api.push.ServiceId.PNI
import java.util.UUID

class OneTimePreKeyTableTest {

  private val aci: ACI = ACI.from(UUID.randomUUID())
  private val pni: PNI = PNI.from(UUID.randomUUID())

  @Test
  fun markAllStaleIfNecessary_onlyUpdatesMatchingAccountAndZeroValues() {
    insertTestRecord(aci, id = 1)
    insertTestRecord(aci, id = 2)
    insertTestRecord(aci, id = 3, staleTime = 42)
    insertTestRecord(pni, id = 4)

    val now = System.currentTimeMillis()
    GenZappDatabase.oneTimePreKeys.markAllStaleIfNecessary(aci, now)

    assertEquals(now, getStaleTime(aci, 1))
    assertEquals(now, getStaleTime(aci, 2))
    assertEquals(42L, getStaleTime(aci, 3))
    assertEquals(0L, getStaleTime(pni, 4))
  }

  @Test
  fun deleteAllStaleBefore_deleteOldBeforeThreshold() {
    insertTestRecord(aci, id = 1, staleTime = 10)
    insertTestRecord(aci, id = 2, staleTime = 10)
    insertTestRecord(aci, id = 3, staleTime = 10)
    insertTestRecord(aci, id = 4, staleTime = 15)
    insertTestRecord(aci, id = 5, staleTime = 0)

    GenZappDatabase.oneTimePreKeys.deleteAllStaleBefore(aci, threshold = 11, minCount = 0)

    assertNull(getStaleTime(aci, 1))
    assertNull(getStaleTime(aci, 2))
    assertNull(getStaleTime(aci, 3))
    assertNotNull(getStaleTime(aci, 4))
    assertNotNull(getStaleTime(aci, 5))
  }

  @Test
  fun deleteAllStaleBefore_neverDeleteStaleOfZero() {
    insertTestRecord(aci, id = 1, staleTime = 0)
    insertTestRecord(aci, id = 2, staleTime = 0)
    insertTestRecord(aci, id = 3, staleTime = 0)
    insertTestRecord(aci, id = 4, staleTime = 0)
    insertTestRecord(aci, id = 5, staleTime = 0)

    GenZappDatabase.oneTimePreKeys.deleteAllStaleBefore(aci, threshold = 10, minCount = 0)

    assertNotNull(getStaleTime(aci, 1))
    assertNotNull(getStaleTime(aci, 2))
    assertNotNull(getStaleTime(aci, 3))
    assertNotNull(getStaleTime(aci, 4))
    assertNotNull(getStaleTime(aci, 5))
  }

  @Test
  fun deleteAllStaleBefore_respectMinCount() {
    insertTestRecord(aci, id = 1, staleTime = 10)
    insertTestRecord(aci, id = 2, staleTime = 10)
    insertTestRecord(aci, id = 3, staleTime = 10)
    insertTestRecord(aci, id = 4, staleTime = 10)
    insertTestRecord(aci, id = 5, staleTime = 10)

    GenZappDatabase.oneTimePreKeys.deleteAllStaleBefore(aci, threshold = 11, minCount = 3)

    assertNull(getStaleTime(aci, 1))
    assertNull(getStaleTime(aci, 2))
    assertNotNull(getStaleTime(aci, 3))
    assertNotNull(getStaleTime(aci, 4))
    assertNotNull(getStaleTime(aci, 5))
  }

  @Test
  fun deleteAllStaleBefore_respectAccount() {
    insertTestRecord(aci, id = 1, staleTime = 10)
    insertTestRecord(aci, id = 2, staleTime = 10)
    insertTestRecord(aci, id = 3, staleTime = 10)

    insertTestRecord(pni, id = 4, staleTime = 10)
    insertTestRecord(pni, id = 5, staleTime = 10)

    GenZappDatabase.oneTimePreKeys.deleteAllStaleBefore(aci, threshold = 11, minCount = 2)

    assertNull(getStaleTime(aci, 1))
    assertNotNull(getStaleTime(aci, 2))
    assertNotNull(getStaleTime(aci, 3))
    assertNotNull(getStaleTime(pni, 4))
    assertNotNull(getStaleTime(pni, 5))
  }

  private fun insertTestRecord(account: ServiceId, id: Int, staleTime: Long = 0) {
    GenZappDatabase.oneTimePreKeys.insert(
      serviceId = account,
      keyId = id,
      record = PreKeyRecord(id, Curve.generateKeyPair())
    )

    val count = GenZappDatabase.rawDatabase
      .update(OneTimePreKeyTable.TABLE_NAME)
      .values(OneTimePreKeyTable.STALE_TIMESTAMP to staleTime)
      .where("${OneTimePreKeyTable.ACCOUNT_ID} = ? AND ${OneTimePreKeyTable.KEY_ID} = $id", account.toAccountId())
      .run()

    assertEquals(1, count)
  }

  private fun getStaleTime(account: ServiceId, id: Int): Long? {
    return GenZappDatabase.rawDatabase
      .select(OneTimePreKeyTable.STALE_TIMESTAMP)
      .from(OneTimePreKeyTable.TABLE_NAME)
      .where("${OneTimePreKeyTable.ACCOUNT_ID} = ? AND ${OneTimePreKeyTable.KEY_ID} = $id", account.toAccountId())
      .run()
      .readToSingleObject { it.requireLongOrNull(OneTimePreKeyTable.STALE_TIMESTAMP) }
  }

  private fun ServiceId.toAccountId(): String {
    return when (this) {
      is ACI -> this.toString()
      is PNI -> OneTimePreKeyTable.PNI_ACCOUNT_ID
    }
  }
}
