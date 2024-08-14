package org.thoughtcrime.securesms.util

import android.content.Context
import android.content.pm.PackageManager
import org.GenZapp.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.RefreshAttributesJob
import org.thoughtcrime.securesms.jobs.RemoteConfigRefreshJob
import org.thoughtcrime.securesms.jobs.RetrieveRemoteAnnouncementsJob
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import java.time.Duration

object VersionTracker {
  private val TAG = Log.tag(VersionTracker::class.java)

  @JvmStatic
  fun getLastSeenVersion(context: Context): Int {
    return TextSecurePreferences.getLastVersionCode(context)
  }

  @JvmStatic
  fun updateLastSeenVersion(context: Context) {
    val currentVersionCode = Util.getCanonicalVersionCode()
    val lastVersionCode = TextSecurePreferences.getLastVersionCode(context)

    if (currentVersionCode != lastVersionCode) {
      Log.i(TAG, "Upgraded from $lastVersionCode to $currentVersionCode. Clearing client deprecation.", true)
      GenZappStore.misc.isClientDeprecated = false
      val jobChain = listOf(RemoteConfigRefreshJob(), RefreshAttributesJob())
      AppDependencies.jobManager.startChain(jobChain).enqueue()
      RetrieveRemoteAnnouncementsJob.enqueue(true)
      LocalMetrics.getInstance().clear()
    }

    TextSecurePreferences.setLastVersionCode(context, currentVersionCode)
  }

  @JvmStatic
  fun getDaysSinceFirstInstalled(context: Context): Long {
    return try {
      val installTimestamp = context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
      Duration.ofMillis(System.currentTimeMillis() - installTimestamp).toDays()
    } catch (e: PackageManager.NameNotFoundException) {
      Log.w(TAG, e)
      0
    }
  }
}
