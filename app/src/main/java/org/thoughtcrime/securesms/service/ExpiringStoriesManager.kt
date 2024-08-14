package org.thoughtcrime.securesms.service

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.WorkerThread
import org.GenZapp.core.util.logging.Log
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import java.util.concurrent.TimeUnit

/**
 * Manages deleting stories 24 hours after they've been sent.
 */
class ExpiringStoriesManager(
  application: Application
) : TimedEventManager<ExpiringStoriesManager.Event>(application, "ExpiringStoriesManager") {

  companion object {
    private val TAG = Log.tag(ExpiringStoriesManager::class.java)

    private val STORY_LIFESPAN = TimeUnit.HOURS.toMillis(24)
  }

  private val mmsDatabase = GenZappDatabase.messages

  init {
    scheduleIfNecessary()
  }

  @WorkerThread
  override fun getNextClosestEvent(): Event? {
    val oldestTimestamp = mmsDatabase.getOldestStorySendTimestamp(GenZappStore.story.userHasViewedOnboardingStory) ?: return null

    val timeSinceSend = System.currentTimeMillis() - oldestTimestamp
    val delay = (STORY_LIFESPAN - timeSinceSend).coerceAtLeast(0)
    Log.i(TAG, "The oldest story needs to be deleted in $delay ms.")

    return Event(delay)
  }

  @WorkerThread
  override fun executeEvent(event: Event) {
    val threshold = System.currentTimeMillis() - STORY_LIFESPAN
    val deletes = mmsDatabase.deleteStoriesOlderThan(threshold, GenZappStore.story.userHasViewedOnboardingStory)
    Log.i(TAG, "Deleted $deletes stories before $threshold")
  }

  @WorkerThread
  override fun getDelayForEvent(event: Event): Long = event.delay

  @WorkerThread
  override fun scheduleAlarm(application: Application, event: Event, delay: Long) {
    setAlarm(application, delay, ExpireStoriesAlarm::class.java)
  }

  data class Event(val delay: Long)

  class ExpireStoriesAlarm : BroadcastReceiver() {

    companion object {
      private val TAG = Log.tag(ExpireStoriesAlarm::class.java)
    }

    override fun onReceive(context: Context?, intent: Intent?) {
      Log.d(TAG, "onReceive()")
      AppDependencies.expireStoriesManager.scheduleIfNecessary()
    }
  }
}
