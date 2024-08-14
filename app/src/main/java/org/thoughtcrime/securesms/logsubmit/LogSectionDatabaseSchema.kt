package org.thoughtcrime.securesms.logsubmit

import android.content.Context
import org.GenZapp.core.util.getAllIndexDefinitions
import org.GenZapp.core.util.getAllTableDefinitions
import org.GenZapp.core.util.getForeignKeys
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.database.helpers.GenZappDatabaseMigrations

/**
 * Renders data pertaining to sender key. While all private info is obfuscated, this is still only intended to be printed for internal users.
 */
class LogSectionDatabaseSchema : LogSection {
  override fun getTitle(): String {
    return "DATABASE SCHEMA"
  }

  override fun getContent(context: Context): CharSequence {
    val builder = StringBuilder()
    builder.append("--- Metadata").append("\n")
    builder.append("Version: ${GenZappDatabaseMigrations.DATABASE_VERSION}\n")
    builder.append("\n\n")

    builder.append("--- Tables").append("\n")
    GenZappDatabase.rawDatabase.getAllTableDefinitions().forEach {
      builder.append(it.statement).append("\n")
    }
    builder.append("\n\n")

    builder.append("--- Indexes").append("\n")
    GenZappDatabase.rawDatabase.getAllIndexDefinitions().forEach {
      builder.append(it.statement).append("\n")
    }
    builder.append("\n\n")

    builder.append("--- Foreign Keys").append("\n")
    GenZappDatabase.rawDatabase.getForeignKeys().forEach {
      builder.append("${it.table}.${it.column} DEPENDS ON ${it.dependsOnTable}.${it.dependsOnColumn}, ON DELETE ${it.onDelete}").append("\n")
    }
    builder.append("\n\n")

    return builder
  }
}
