/*
 * Copyright 2023 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.backup.v2.processor

import org.GenZapp.core.util.logging.Log
import org.thoughtcrime.securesms.backup.v2.BackupState
import org.thoughtcrime.securesms.backup.v2.database.getAdhocCallsForBackup
import org.thoughtcrime.securesms.backup.v2.database.restoreCallLogFromBackup
import org.thoughtcrime.securesms.backup.v2.proto.AdHocCall
import org.thoughtcrime.securesms.backup.v2.proto.Frame
import org.thoughtcrime.securesms.backup.v2.stream.BackupFrameEmitter
import org.thoughtcrime.securesms.database.GenZappDatabase

object AdHocCallBackupProcessor {

  val TAG = Log.tag(AdHocCallBackupProcessor::class.java)

  fun export(db: GenZappDatabase, emitter: BackupFrameEmitter) {
    db.callTable.getAdhocCallsForBackup().use { reader ->
      for (callLog in reader) {
        if (callLog != null) {
          emitter.emit(Frame(adHocCall = callLog))
        }
      }
    }
  }

  fun import(call: AdHocCall, backupState: BackupState) {
    GenZappDatabase.calls.restoreCallLogFromBackup(call, backupState)
  }
}
