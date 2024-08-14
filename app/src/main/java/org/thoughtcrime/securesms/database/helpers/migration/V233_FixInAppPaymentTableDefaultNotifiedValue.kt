/*
 * Copyright 2024 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Updates notified default value from 0 to 1
 */
@Suppress("ClassName")
object V233_FixInAppPaymentTableDefaultNotifiedValue : GenZappDatabaseMigration {
  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    db.execSQL(
      """
      CREATE TABLE in_app_payment_tmp (
        _id INTEGER PRIMARY KEY,
        type INTEGER NOT NULL,
        state INTEGER NOT NULL,
        inserted_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        notified INTEGER DEFAULT 1,
        subscriber_id TEXT,
        end_of_period INTEGER DEFAULT 0,
        data BLOB NOT NULL
      )
      """.trimIndent()
    )

    db.execSQL(
      """
        INSERT INTO in_app_payment_tmp
        SELECT
            _id,
            type,
            state,
            inserted_at,
            updated_at,
            notified,
            subscriber_id,
            end_of_period,
            data
        FROM in_app_payment
      """.trimIndent()
    )

    db.execSQL("DROP TABLE in_app_payment")
    db.execSQL("ALTER TABLE in_app_payment_tmp RENAME TO in_app_payment")
  }
}
