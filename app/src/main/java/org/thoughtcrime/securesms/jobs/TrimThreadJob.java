/*
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.GenZapp.core.util.concurrent.GenZappExecutors;
import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.database.ThreadTable;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.JsonJobData;
import org.thoughtcrime.securesms.keyvalue.KeepMessagesDuration;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.recipients.Recipient;

public class TrimThreadJob extends BaseJob {

  public static final String KEY = "TrimThreadJob";

  private static final String TAG           = Log.tag(TrimThreadJob.class);
  private static final String QUEUE_PREFIX  = "TrimThreadJob_";
  private static final String KEY_THREAD_ID = "thread_id";

  private final long threadId;

  public static void enqueueAsync(long threadId) {
    if (GenZappStore.settings().getKeepMessagesDuration() != KeepMessagesDuration.FOREVER || GenZappStore.settings().isTrimByLengthEnabled()) {
      GenZappExecutors.BOUNDED.execute(() -> AppDependencies.getJobManager().add(new TrimThreadJob(threadId)));
    }
  }

  private TrimThreadJob(long threadId) {
    this(new Job.Parameters.Builder().setQueue(QUEUE_PREFIX + threadId)
                                     .setMaxInstancesForQueue(2)
                                     .build(),
         threadId);
  }

  private TrimThreadJob(@NonNull Job.Parameters parameters, long threadId) {
    super(parameters);
    this.threadId = threadId;
  }

  @Override
  public @Nullable byte[] serialize() {
    return new JsonJobData.Builder().putLong(KEY_THREAD_ID, threadId).serialize();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() {
    KeepMessagesDuration keepMessagesDuration = GenZappStore.settings().getKeepMessagesDuration();

    int trimLength = GenZappStore.settings().isTrimByLengthEnabled() ? GenZappStore.settings().getThreadTrimLength()
                                                                    : ThreadTable.NO_TRIM_MESSAGE_COUNT_SET;

    long trimBeforeDate = keepMessagesDuration != KeepMessagesDuration.FOREVER ? System.currentTimeMillis() - keepMessagesDuration.getDuration()
                                                                               : ThreadTable.NO_TRIM_BEFORE_DATE_SET;

    GenZappDatabase.threads().trimThread(threadId, GenZappStore.settings().shouldSyncThreadTrimDeletes() && Recipient.self().getDeleteSyncCapability().isSupported(), trimLength, trimBeforeDate, false);
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception exception) {
    return false;
  }

  @Override
  public void onFailure() {
    Log.w(TAG, "Canceling trim attempt: " + threadId);
  }

  public static final class Factory implements Job.Factory<TrimThreadJob> {
    @Override
    public @NonNull TrimThreadJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      JsonJobData data = JsonJobData.deserialize(serializedData);
      return new TrimThreadJob(parameters, data.getLong(KEY_THREAD_ID));
    }
  }
}
