/*
 * Copyright 2023 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.storage.StorageRecordUpdate
import org.thoughtcrime.securesms.storage.StorageSyncModels
import org.thoughtcrime.securesms.testing.GenZappActivityRule
import org.thoughtcrime.securesms.testing.assertIs
import org.thoughtcrime.securesms.util.MessageTableTestUtils
import org.whispersystems.GenZappservice.api.storage.GenZappContactRecord
import org.whispersystems.GenZappservice.internal.storage.protos.ContactRecord

@Suppress("ClassName")
@RunWith(AndroidJUnit4::class)
class RecipientTableTest_applyStorageSyncContactUpdate {
  @get:Rule
  val harness = GenZappActivityRule()

  @Test
  fun insertMessageOnVerifiedToDefault() {
    // GIVEN
    val identities = AppDependencies.protocolStore.aci().identities()
    val other = Recipient.resolved(harness.others[0])

    MmsHelper.insert(recipient = other)
    identities.setVerified(other.id, harness.othersKeys[0].publicKey, IdentityTable.VerifiedStatus.VERIFIED)

    val oldRecord: GenZappContactRecord = StorageSyncModels.localToRemoteRecord(GenZappDatabase.recipients.getRecordForSync(harness.others[0])!!).contact.get()

    val newProto = oldRecord
      .toProto()
      .newBuilder()
      .identityState(ContactRecord.IdentityState.DEFAULT)
      .build()
    val newRecord = GenZappContactRecord(oldRecord.id, newProto)

    val update = StorageRecordUpdate<GenZappContactRecord>(oldRecord, newRecord)

    // WHEN
    val oldVerifiedStatus: IdentityTable.VerifiedStatus = identities.getIdentityRecord(other.id).get().verifiedStatus
    GenZappDatabase.recipients.applyStorageSyncContactUpdate(update)
    val newVerifiedStatus: IdentityTable.VerifiedStatus = identities.getIdentityRecord(other.id).get().verifiedStatus

    // THEN
    oldVerifiedStatus assertIs IdentityTable.VerifiedStatus.VERIFIED
    newVerifiedStatus assertIs IdentityTable.VerifiedStatus.DEFAULT

    val messages = MessageTableTestUtils.getMessages(GenZappDatabase.threads.getThreadIdFor(other.id)!!)
    messages.first().isIdentityDefault assertIs true
  }
}
