package org.thoughtcrime.securesms.components.reminder;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.util.CommunicationActions;
import org.thoughtcrime.securesms.util.PlayStoreUtil;

import java.util.List;

/**
 * Showed when a build has fully expired (either via the compile-time constant, or remote
 * deprecation).
 */
public class ExpiredBuildReminder extends Reminder {

  public ExpiredBuildReminder(final Context context) {
    super(R.string.ExpiredBuildReminder_this_version_of_GenZapp_has_expired);
    addAction(new Action(R.string.ExpiredBuildReminder_update_now, R.id.reminder_action_update_now));
  }

  @Override
  public boolean isDismissable() {
    return false;
  }

  @Override
  public @NonNull Importance getImportance() {
    return Importance.TERMINAL;
  }

  public static boolean isEligible() {
    return GenZappStore.misc().isClientDeprecated();
  }
}
