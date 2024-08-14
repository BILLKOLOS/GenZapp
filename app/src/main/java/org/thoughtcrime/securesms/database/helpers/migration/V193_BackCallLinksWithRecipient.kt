/*
 * Copyright 2023 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Due to a bug, this has been replaced by [V196_BackCallLinksWithRecipientV2]
 */
@Suppress("ClassName")
object V193_BackCallLinksWithRecipient : GenZappDatabaseMigration {

  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}
