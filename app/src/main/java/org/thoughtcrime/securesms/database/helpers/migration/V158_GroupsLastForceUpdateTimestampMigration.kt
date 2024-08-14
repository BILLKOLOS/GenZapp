package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Track last time we did a forced sanity check for this group with the server.
 */
@Suppress("ClassName")
object V158_GroupsLastForceUpdateTimestampMigration : GenZappDatabaseMigration {
  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    db.execSQL("ALTER TABLE groups ADD COLUMN last_force_update_timestamp INTEGER DEFAULT 0")
  }
}
