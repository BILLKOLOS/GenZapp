/*
 * Copyright 2023 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.backup.v2.processor

import org.GenZapp.core.util.logging.Log
import org.thoughtcrime.securesms.backup.v2.BackupState
import org.thoughtcrime.securesms.backup.v2.ExportState
import org.thoughtcrime.securesms.backup.v2.database.BackupRecipient
import org.thoughtcrime.securesms.backup.v2.database.getAllForBackup
import org.thoughtcrime.securesms.backup.v2.database.getCallLinksForBackup
import org.thoughtcrime.securesms.backup.v2.database.getContactsForBackup
import org.thoughtcrime.securesms.backup.v2.database.getGroupsForBackup
import org.thoughtcrime.securesms.backup.v2.database.restoreContactFromBackup
import org.thoughtcrime.securesms.backup.v2.database.restoreFromBackup
import org.thoughtcrime.securesms.backup.v2.database.restoreGroupFromBackup
import org.thoughtcrime.securesms.backup.v2.database.restoreReleaseNotes
import org.thoughtcrime.securesms.backup.v2.proto.Frame
import org.thoughtcrime.securesms.backup.v2.proto.ReleaseNotes
import org.thoughtcrime.securesms.backup.v2.stream.BackupFrameEmitter
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.recipients.Recipient

object RecipientBackupProcessor {

  val TAG = Log.tag(RecipientBackupProcessor::class.java)

  fun export(db: GenZappDatabase, GenZappStore: GenZappStore, state: ExportState, emitter: BackupFrameEmitter) {
    val selfId = db.recipientTable.getByAci(GenZappStore.accountValues.aci!!).get().toLong()
    val releaseChannelId = GenZappStore.releaseChannelValues.releaseChannelRecipientId
    if (releaseChannelId != null) {
      state.recipientIds.add(releaseChannelId.toLong())
      emitter.emit(
        Frame(
          recipient = BackupRecipient(
            id = releaseChannelId.toLong(),
            releaseNotes = ReleaseNotes()
          )
        )
      )
    }

    db.recipientTable.getContactsForBackup(selfId).use { reader ->
      for (backupRecipient in reader) {
        if (backupRecipient != null) {
          state.recipientIds.add(backupRecipient.id)
          emitter.emit(Frame(recipient = backupRecipient))
        }
      }
    }

    db.recipientTable.getGroupsForBackup().use { reader ->
      for (backupRecipient in reader) {
        state.recipientIds.add(backupRecipient.id)
        emitter.emit(Frame(recipient = backupRecipient))
      }
    }

    db.distributionListTables.getAllForBackup().forEach {
      state.recipientIds.add(it.id)
      emitter.emit(Frame(recipient = it))
    }

    db.callLinkTable.getCallLinksForBackup().forEach {
      state.recipientIds.add(it.id)
      emitter.emit(Frame(recipient = it))
    }
  }

  fun import(recipient: BackupRecipient, backupState: BackupState) {
    val newId = when {
      recipient.contact != null -> GenZappDatabase.recipients.restoreContactFromBackup(recipient.contact)
      recipient.group != null -> GenZappDatabase.recipients.restoreGroupFromBackup(recipient.group)
      recipient.distributionList != null -> GenZappDatabase.distributionLists.restoreFromBackup(recipient.distributionList, backupState)
      recipient.self != null -> Recipient.self().id
      recipient.releaseNotes != null -> GenZappDatabase.recipients.restoreReleaseNotes()
      recipient.callLink != null -> GenZappDatabase.callLinks.restoreFromBackup(recipient.callLink)
      else -> {
        Log.w(TAG, "Unrecognized recipient type!")
        null
      }
    }
    if (newId != null) {
      backupState.backupToLocalRecipientId[recipient.id] = newId
    }
  }
}
