package org.thoughtcrime.securesms.logsubmit;

import android.content.Context;

import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.keyvalue.GenZappStore;

public class LogSectionPin implements LogSection {

  @Override
  public @NonNull String getTitle() {
    return "PIN STATE";
  }

  @Override
  public @NonNull CharSequence getContent(@NonNull Context context) {
    return new StringBuilder().append("Last Successful Reminder Entry: ").append(GenZappStore.pin().getLastSuccessfulEntryTime()).append("\n")
                              .append("Next Reminder Interval: ").append(GenZappStore.pin().getCurrentInterval()).append("\n")
                              .append("Reglock: ").append(GenZappStore.svr().isRegistrationLockEnabled()).append("\n")
                              .append("GenZapp PIN: ").append(GenZappStore.svr().hasPin()).append("\n")
                              .append("Opted Out: ").append(GenZappStore.svr().hasOptedOut()).append("\n")
                              .append("Last Creation Failed: ").append(GenZappStore.svr().lastPinCreateFailed()).append("\n")
                              .append("Needs Account Restore: ").append(GenZappStore.storageService().needsAccountRestore()).append("\n")
                              .append("PIN Required at Registration: ").append(GenZappStore.registration().pinWasRequiredAtRegistration()).append("\n")
                              .append("Registration Complete: ").append(GenZappStore.registration().isRegistrationComplete());

  }
}
