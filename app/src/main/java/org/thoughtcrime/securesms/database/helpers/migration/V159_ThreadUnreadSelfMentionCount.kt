package org.thoughtcrime.securesms.database.helpers.migration

import android.app.Application
import net.zetetic.database.sqlcipher.SQLiteDatabase

@Suppress("ClassName")
object V159_ThreadUnreadSelfMentionCount : GenZappDatabaseMigration {
  override fun migrate(context: Application, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    db.execSQL("ALTER TABLE thread ADD COLUMN unread_self_mention_count INTEGER DEFAULT 0")
  }
}
