package org.thoughtcrime.securesms.components.settings.app.data

import android.content.Context
import org.GenZapp.core.util.concurrent.GenZappExecutors
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies

class DataAndStorageSettingsRepository {

  private val context: Context = AppDependencies.application

  fun getTotalStorageUse(consumer: (Long) -> Unit) {
    GenZappExecutors.BOUNDED.execute {
      val breakdown = GenZappDatabase.media.getStorageBreakdown()

      consumer(listOf(breakdown.audioSize, breakdown.documentSize, breakdown.photoSize, breakdown.videoSize).sum())
    }
  }
}
