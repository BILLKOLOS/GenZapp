package org.thoughtcrime.securesms.logsubmit;

import android.content.Context;

import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.keyvalue.KeepMessagesDuration;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;

final class LogSectionKeyPreferences implements LogSection {

  @Override
  public @NonNull String getTitle() {
    return "KEY PREFERENCES";
  }

  @Override
  public @NonNull CharSequence getContent(@NonNull Context context) {
    return new StringBuilder().append("Screen Lock              : ").append(TextSecurePreferences.isScreenLockEnabled(context)).append("\n")
                              .append("Screen Lock Timeout      : ").append(TextSecurePreferences.getScreenLockTimeout(context)).append("\n")
                              .append("Password Disabled        : ").append(TextSecurePreferences.isPasswordDisabled(context)).append("\n")
                              .append("Prefer Contact Photos    : ").append(GenZappStore.settings().isPreferSystemContactPhotos()).append("\n")
                              .append("Call Data Mode           : ").append(GenZappStore.settings().getCallDataMode()).append("\n")
                              .append("Media Quality            : ").append(GenZappStore.settings().getSentMediaQuality()).append("\n")
                              .append("Client Deprecated        : ").append(GenZappStore.misc().isClientDeprecated()).append("\n")
                              .append("Push Registered          : ").append(GenZappStore.account().isRegistered()).append("\n")
                              .append("Unauthorized Received    : ").append(TextSecurePreferences.isUnauthorizedReceived(context)).append("\n")
                              .append("self.isRegistered()      : ").append(GenZappStore.account().getAci() == null ? "false"     : Recipient.self().isRegistered()).append("\n")
                              .append("Thread Trimming          : ").append(getThreadTrimmingString()).append("\n")
                              .append("Censorship Setting       : ").append(GenZappStore.settings().getCensorshipCircumventionEnabled()).append("\n")
                              .append("Network Reachable        : ").append(GenZappStore.misc().isServiceReachableWithoutCircumvention()).append(", last checked: ").append(GenZappStore.misc().getLastCensorshipServiceReachabilityCheckTime()).append("\n")
                              .append("Wifi Download            : ").append(Util.join(TextSecurePreferences.getWifiMediaDownloadAllowed(context), ",")).append("\n")
                              .append("Roaming Download         : ").append(Util.join(TextSecurePreferences.getRoamingMediaDownloadAllowed(context), ",")).append("\n")
                              .append("Mobile Download          : ").append(Util.join(TextSecurePreferences.getMobileMediaDownloadAllowed(context), ",")).append("\n")
                              .append("Phone Number Sharing     : ").append(GenZappStore.phoneNumberPrivacy().isPhoneNumberSharingEnabled()).append(" (").append(GenZappStore.phoneNumberPrivacy().getPhoneNumberSharingMode()).append(")\n")
                              .append("Phone Number Discoverable: ").append(GenZappStore.phoneNumberPrivacy().getPhoneNumberDiscoverabilityMode()).append("\n");
  }

  private static String getThreadTrimmingString() {
    if (GenZappStore.settings().isTrimByLengthEnabled()) {
      return "Enabled - Max length of " + GenZappStore.settings().getThreadTrimLength();
    } else if (GenZappStore.settings().getKeepMessagesDuration() != KeepMessagesDuration.FOREVER) {
      return "Enabled - Max age of " + GenZappStore.settings().getKeepMessagesDuration();
    } else {
      return "Disabled";
    }
  }
}
