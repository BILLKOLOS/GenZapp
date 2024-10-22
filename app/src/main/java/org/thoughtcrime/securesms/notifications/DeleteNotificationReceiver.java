package org.thoughtcrime.securesms.notifications;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.GenZapp.core.util.concurrent.GenZappExecutors;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.notifications.v2.ConversationId;

import java.util.ArrayList;

public class DeleteNotificationReceiver extends BroadcastReceiver {

  public static String DELETE_NOTIFICATION_ACTION = "org.thoughtcrime.securesms.DELETE_NOTIFICATION";

  public static final String EXTRA_IDS     = "message_ids";
  public static final String EXTRA_MMS     = "is_mms";
  public static final String EXTRA_THREADS = "threads";

  @Override
  public void onReceive(final Context context, Intent intent) {
    if (DELETE_NOTIFICATION_ACTION.equals(intent.getAction())) {
      MessageNotifier notifier = AppDependencies.getMessageNotifier();

      final long[]                        ids     = intent.getLongArrayExtra(EXTRA_IDS);
      final boolean[]                 mms     = intent.getBooleanArrayExtra(EXTRA_MMS);
      final ArrayList<ConversationId> threads = intent.getParcelableArrayListExtra(EXTRA_THREADS);

      if (threads != null) {
        for (ConversationId thread : threads) {
          notifier.removeStickyThread(thread);
        }
      }

      if (ids == null || mms == null || ids.length != mms.length) return;

      PendingResult finisher = goAsync();

      GenZappExecutors.BOUNDED.execute(() -> {
        for (int i = 0; i < ids.length; i++) {
          if (!mms[i]) {
            GenZappDatabase.messages().markAsNotified(ids[i]);
          } else {
            GenZappDatabase.messages().markAsNotified(ids[i]);
          }
        }
        finisher.finish();
      });
    }
  }
}
