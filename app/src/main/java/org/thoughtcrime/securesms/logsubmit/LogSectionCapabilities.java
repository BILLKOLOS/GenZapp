package org.thoughtcrime.securesms.logsubmit;

import android.content.Context;

import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.AppCapabilities;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.database.model.RecipientRecord;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.GenZappservice.api.account.AccountAttributes;

public final class LogSectionCapabilities implements LogSection {

  @Override
  public @NonNull String getTitle() {
    return "CAPABILITIES";
  }

  @Override
  public @NonNull CharSequence getContent(@NonNull Context context) {
    if (!GenZappStore.account().isRegistered()) {
      return "Unregistered";
    }

    if (GenZappStore.account().getE164() == null || GenZappStore.account().getAci() == null) {
      return "Self not yet available!";
    }

    Recipient self = Recipient.self();

    AccountAttributes.Capabilities localCapabilities  = AppCapabilities.getCapabilities(false);
    RecipientRecord.Capabilities   globalCapabilities = GenZappDatabase.recipients().getCapabilities(self.getId());

    StringBuilder builder = new StringBuilder().append("-- Local").append("\n")
                                               .append("DeleteSync: ").append(localCapabilities.getDeleteSync()).append("\n")
                                               .append("\n")
                                               .append("-- Global").append("\n");

    if (globalCapabilities != null) {
      builder.append("DeleteSync: ").append(globalCapabilities.getDeleteSync()).append("\n");
      builder.append("\n");
    } else {
      builder.append("Self not found!");
    }

    return builder;
  }
}
