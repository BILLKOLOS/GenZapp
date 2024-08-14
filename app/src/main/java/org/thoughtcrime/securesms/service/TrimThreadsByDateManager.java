package org.thoughtcrime.securesms.service;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.database.MessageTable;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.database.ThreadTable;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.keyvalue.KeepMessagesDuration;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;

public class TrimThreadsByDateManager extends TimedEventManager<TrimThreadsByDateManager.TrimEvent> {

  private static final String TAG = Log.tag(TrimThreadsByDateManager.class);

  private final ThreadTable  threadTable;
  private final MessageTable messageTable;

  public TrimThreadsByDateManager(@NonNull Application application) {
    super(application, "TrimThreadsByDateManager");

    threadTable  = GenZappDatabase.threads();
    messageTable = GenZappDatabase.messages();

    scheduleIfNecessary();
  }

  @Override
  protected @Nullable TrimEvent getNextClosestEvent() {
    KeepMessagesDuration keepMessagesDuration = GenZappStore.settings().getKeepMessagesDuration();
    if (keepMessagesDuration == KeepMessagesDuration.FOREVER) {
      return null;
    }

    long trimBeforeDate = System.currentTimeMillis() - keepMessagesDuration.getDuration();

    if (messageTable.getMessageCountBeforeDate(trimBeforeDate) > 0) {
      Log.i(TAG, "Messages exist before date, trim immediately");
      return new TrimEvent(0);
    }

    long timestamp = messageTable.getTimestampForFirstMessageAfterDate(trimBeforeDate);

    if (timestamp == 0) {
      return null;
    }

    return new TrimEvent(Math.max(0, keepMessagesDuration.getDuration() - (System.currentTimeMillis() - timestamp)));
  }

  @Override
  protected void executeEvent(@NonNull TrimEvent event) {
    KeepMessagesDuration keepMessagesDuration = GenZappStore.settings().getKeepMessagesDuration();

    int trimLength = GenZappStore.settings().isTrimByLengthEnabled() ? GenZappStore.settings().getThreadTrimLength()
                                                                    : ThreadTable.NO_TRIM_MESSAGE_COUNT_SET;

    long trimBeforeDate = keepMessagesDuration != KeepMessagesDuration.FOREVER ? System.currentTimeMillis() - keepMessagesDuration.getDuration()
                                                                               : ThreadTable.NO_TRIM_BEFORE_DATE_SET;

    Log.i(TAG, "Trimming all threads with length: " + trimLength + " before: " + trimBeforeDate);
    threadTable.trimAllThreads(trimLength, trimBeforeDate);
  }

  @Override
  protected long getDelayForEvent(@NonNull TrimEvent event) {
    return event.delay;
  }

  @Override
  protected void scheduleAlarm(@NonNull Application application, TrimEvent event, long delay) {
    setAlarm(application, delay, TrimThreadsByDateAlarm.class);
  }

  public static class TrimThreadsByDateAlarm extends BroadcastReceiver {

    private static final String TAG = Log.tag(TrimThreadsByDateAlarm.class);

    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
      Log.d(TAG, "onReceive()");
      AppDependencies.getTrimThreadsByDateManager().scheduleIfNecessary();
    }
  }

  public static class TrimEvent {
    final long delay;

    public TrimEvent(long delay) {
      this.delay = delay;
    }
  }
}
