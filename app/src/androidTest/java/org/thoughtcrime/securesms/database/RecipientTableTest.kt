package org.thoughtcrime.securesms.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.GenZapp.core.util.CursorUtil
import org.thoughtcrime.securesms.profiles.ProfileName
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testing.GenZappActivityRule
import org.whispersystems.GenZappservice.api.push.ServiceId.ACI
import org.whispersystems.GenZappservice.api.push.ServiceId.PNI
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecipientTableTest {

  @get:Rule
  val harness = GenZappActivityRule()

  @Test
  fun givenAHiddenRecipient_whenIQueryAllContacts_thenIExpectHiddenToBeReturned() {
    val hiddenRecipient = harness.others[0]
    GenZappDatabase.recipients.setProfileName(hiddenRecipient, ProfileName.fromParts("Hidden", "Person"))
    GenZappDatabase.recipients.markHidden(hiddenRecipient)

    val results = GenZappDatabase.recipients.queryAllContacts("Hidden")!!

    assertEquals(1, results.count)
  }

  @Test
  fun givenAHiddenRecipient_whenIGetGenZappContacts_thenIDoNotExpectHiddenToBeReturned() {
    val hiddenRecipient = harness.others[0]
    GenZappDatabase.recipients.setProfileName(hiddenRecipient, ProfileName.fromParts("Hidden", "Person"))
    GenZappDatabase.recipients.markHidden(hiddenRecipient)

    val results: MutableList<RecipientId> = GenZappDatabase.recipients.getGenZappContacts(false)?.use {
      val ids = mutableListOf<RecipientId>()
      while (it.moveToNext()) {
        ids.add(RecipientId.from(CursorUtil.requireLong(it, RecipientTable.ID)))
      }

      ids
    }!!

    assertNotEquals(0, results.size)
    assertFalse(hiddenRecipient in results)
  }

  @Test
  fun givenAHiddenRecipient_whenIQueryGenZappContacts_thenIDoNotExpectHiddenToBeReturned() {
    val hiddenRecipient = harness.others[0]
    GenZappDatabase.recipients.setProfileName(hiddenRecipient, ProfileName.fromParts("Hidden", "Person"))
    GenZappDatabase.recipients.markHidden(hiddenRecipient)

    val results = GenZappDatabase.recipients.queryGenZappContacts(RecipientTable.ContactSearchQuery("Hidden", false))!!

    assertEquals(0, results.count)
  }

  @Test
  fun givenAHiddenRecipient_whenIQueryNonGroupContacts_thenIDoNotExpectHiddenToBeReturned() {
    val hiddenRecipient = harness.others[0]
    GenZappDatabase.recipients.setProfileName(hiddenRecipient, ProfileName.fromParts("Hidden", "Person"))
    GenZappDatabase.recipients.markHidden(hiddenRecipient)

    val results = GenZappDatabase.recipients.queryNonGroupContacts("Hidden", false)!!

    assertEquals(0, results.count)
  }

  @Test
  fun givenAHiddenRecipient_whenIGetNonGroupContacts_thenIDoNotExpectHiddenToBeReturned() {
    val hiddenRecipient = harness.others[0]
    GenZappDatabase.recipients.setProfileName(hiddenRecipient, ProfileName.fromParts("Hidden", "Person"))
    GenZappDatabase.recipients.markHidden(hiddenRecipient)

    val results: MutableList<RecipientId> = GenZappDatabase.recipients.getNonGroupContacts(false)?.use {
      val ids = mutableListOf<RecipientId>()
      while (it.moveToNext()) {
        ids.add(RecipientId.from(CursorUtil.requireLong(it, RecipientTable.ID)))
      }

      ids
    }!!

    assertNotEquals(0, results.size)
    assertFalse(hiddenRecipient in results)
  }

  @Test
  fun givenABlockedRecipient_whenIQueryAllContacts_thenIDoNotExpectBlockedToBeReturned() {
    val blockedRecipient = harness.others[0]
    GenZappDatabase.recipients.setProfileName(blockedRecipient, ProfileName.fromParts("Blocked", "Person"))
    GenZappDatabase.recipients.setBlocked(blockedRecipient, true)

    val results = GenZappDatabase.recipients.queryAllContacts("Blocked")!!

    assertEquals(0, results.count)
  }

  @Test
  fun givenABlockedRecipient_whenIGetGenZappContacts_thenIDoNotExpectBlockedToBeReturned() {
    val blockedRecipient = harness.others[0]
    GenZappDatabase.recipients.setProfileName(blockedRecipient, ProfileName.fromParts("Blocked", "Person"))
    GenZappDatabase.recipients.setBlocked(blockedRecipient, true)

    val results: MutableList<RecipientId> = GenZappDatabase.recipients.getGenZappContacts(false)?.use {
      val ids = mutableListOf<RecipientId>()
      while (it.moveToNext()) {
        ids.add(RecipientId.from(CursorUtil.requireLong(it, RecipientTable.ID)))
      }

      ids
    }!!

    assertNotEquals(0, results.size)
    assertFalse(blockedRecipient in results)
  }

  @Test
  fun givenABlockedRecipient_whenIQueryGenZappContacts_thenIDoNotExpectBlockedToBeReturned() {
    val blockedRecipient = harness.others[0]
    GenZappDatabase.recipients.setProfileName(blockedRecipient, ProfileName.fromParts("Blocked", "Person"))
    GenZappDatabase.recipients.setBlocked(blockedRecipient, true)

    val results = GenZappDatabase.recipients.queryGenZappContacts(RecipientTable.ContactSearchQuery("Blocked", false))!!

    assertEquals(0, results.count)
  }

  @Test
  fun givenABlockedRecipient_whenIQueryNonGroupContacts_thenIDoNotExpectBlockedToBeReturned() {
    val blockedRecipient = harness.others[0]
    GenZappDatabase.recipients.setProfileName(blockedRecipient, ProfileName.fromParts("Blocked", "Person"))
    GenZappDatabase.recipients.setBlocked(blockedRecipient, true)

    val results = GenZappDatabase.recipients.queryNonGroupContacts("Blocked", false)!!

    assertEquals(0, results.count)
  }

  @Test
  fun givenABlockedRecipient_whenIGetNonGroupContacts_thenIDoNotExpectBlockedToBeReturned() {
    val blockedRecipient = harness.others[0]
    GenZappDatabase.recipients.setProfileName(blockedRecipient, ProfileName.fromParts("Blocked", "Person"))
    GenZappDatabase.recipients.setBlocked(blockedRecipient, true)

    val results: MutableList<RecipientId> = GenZappDatabase.recipients.getNonGroupContacts(false)?.use {
      val ids = mutableListOf<RecipientId>()
      while (it.moveToNext()) {
        ids.add(RecipientId.from(CursorUtil.requireLong(it, RecipientTable.ID)))
      }

      ids
    }!!

    assertNotEquals(0, results.size)
    assertFalse(blockedRecipient in results)
  }

  @Test
  fun givenARecipientWithPniAndAci_whenIMarkItUnregistered_thenIExpectItToBeSplit() {
    val mainId = GenZappDatabase.recipients.getAndPossiblyMerge(ACI_A, PNI_A, E164_A)

    GenZappDatabase.recipients.markUnregistered(mainId)

    val byAci: RecipientId = GenZappDatabase.recipients.getByAci(ACI_A).get()

    val byE164: RecipientId = GenZappDatabase.recipients.getByE164(E164_A).get()
    val byPni: RecipientId = GenZappDatabase.recipients.getByPni(PNI_A).get()

    assertEquals(mainId, byAci)
    assertEquals(byE164, byPni)
    assertNotEquals(byAci, byE164)
  }

  @Test
  fun givenARecipientWithPniAndAci_whenISplitItForStorageSync_thenIExpectItToBeSplit() {
    val mainId = GenZappDatabase.recipients.getAndPossiblyMerge(ACI_A, PNI_A, E164_A)
    val mainRecord = GenZappDatabase.recipients.getRecord(mainId)

    GenZappDatabase.recipients.splitForStorageSyncIfNecessary(mainRecord.aci!!)

    val byAci: RecipientId = GenZappDatabase.recipients.getByAci(ACI_A).get()

    val byE164: RecipientId = GenZappDatabase.recipients.getByE164(E164_A).get()
    val byPni: RecipientId = GenZappDatabase.recipients.getByPni(PNI_A).get()

    assertEquals(mainId, byAci)
    assertEquals(byE164, byPni)
    assertNotEquals(byAci, byE164)
  }

  companion object {
    val ACI_A = ACI.from(UUID.fromString("aaaa0000-5a76-47fa-a98a-7e72c948a82e"))
    val PNI_A = PNI.from(UUID.fromString("aaaa1111-c960-4f6c-8385-671ad2ffb999"))
    const val E164_A = "+12222222222"
  }
}
