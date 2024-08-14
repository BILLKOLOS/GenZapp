package org.thoughtcrime.securesms.megaphone;

import org.thoughtcrime.securesms.keyvalue.GenZappStore;

final class GenZappPinReminderSchedule implements MegaphoneSchedule {

  @Override
  public boolean shouldDisplay(int seenCount, long lastSeen, long firstVisible, long currentTime) {
    if (GenZappStore.svr().hasOptedOut()) {
      return false;
    }

    if (!GenZappStore.svr().hasPin()) {
      return false;
    }

    if (!GenZappStore.pin().arePinRemindersEnabled()) {
      return false;
    }

    if (!GenZappStore.account().isRegistered()) {
      return false;
    }

    long lastSuccessTime = GenZappStore.pin().getLastSuccessfulEntryTime();
    long interval        = GenZappStore.pin().getCurrentInterval();

    return currentTime - lastSuccessTime >= interval;
  }
}
