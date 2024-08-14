package org.thoughtcrime.securesms.ratelimit;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobs.PushGroupSendJob;
import org.thoughtcrime.securesms.jobs.IndividualSendJob;

import java.util.Set;

public final class RateLimitUtil {

  private static final String TAG = Log.tag(RateLimitUtil.class);

  private RateLimitUtil() {}

  /**
   * Forces a retry of all rate limited messages by editing jobs that are in the queue.
   */
  @WorkerThread
  public static void retryAllRateLimitedMessages(@NonNull Context context) {
    Set<Long> messageIds = GenZappDatabase.messages().getAllRateLimitedMessageIds();

    if (messageIds.isEmpty()) {
      return;
    }

    Log.i(TAG, "Retrying " + messageIds.size() + " message records.");

    GenZappDatabase.messages().clearRateLimitStatus(messageIds);

    AppDependencies.getJobManager().update((job) -> {
      if (job.getFactoryKey().equals(IndividualSendJob.KEY) && messageIds.contains(IndividualSendJob.getMessageId(job.getSerializedData()))) {
        return job.withNextBackoffInterval(0);
      } else if (job.getFactoryKey().equals(PushGroupSendJob.KEY) && messageIds.contains(PushGroupSendJob.getMessageId(job.getSerializedData()))) {
        return job.withNextBackoffInterval(0);
      } else {
        return job;
      }
    });
  }
}
