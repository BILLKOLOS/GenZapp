/*
 * Copyright 2023 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.migrations

import org.GenZapp.core.util.logging.Log
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import java.util.Random

internal class BackupJitterMigrationJob(parameters: Parameters = Parameters.Builder().build()) : MigrationJob(parameters) {
  companion object {
    const val KEY = "BackupJitterMigrationJob"
    val TAG = Log.tag(BackupJitterMigrationJob::class.java)
  }

  override fun getFactoryKey(): String = KEY

  override fun isUiBlocking(): Boolean = false

  override fun performMigration() {
    val hour = GenZappStore.settings.backupHour
    val minute = GenZappStore.settings.backupMinute
    if (hour == SettingsValues.BACKUP_DEFAULT_HOUR && minute == SettingsValues.BACKUP_DEFAULT_MINUTE) {
      val rand = Random()
      val newHour = rand.nextInt(3) + 1 // between 1AM - 3AM
      val newMinute = rand.nextInt(12) * 5 // 5 minute intervals up to +55 minutes
      GenZappStore.settings.setBackupSchedule(newHour, newMinute)
    }
  }

  override fun shouldRetry(e: Exception): Boolean = false

  class Factory : Job.Factory<BackupJitterMigrationJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): BackupJitterMigrationJob {
      return BackupJitterMigrationJob(parameters)
    }
  }
}
