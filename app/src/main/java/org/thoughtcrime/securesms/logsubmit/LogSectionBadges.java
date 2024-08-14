package org.thoughtcrime.securesms.logsubmit;

import android.content.Context;

import androidx.annotation.NonNull;

import org.GenZapp.donations.InAppPaymentType;
import org.thoughtcrime.securesms.components.settings.app.subscription.InAppPaymentsRepository;
import org.thoughtcrime.securesms.database.InAppPaymentTable;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.database.model.InAppPaymentSubscriberRecord;
import org.thoughtcrime.securesms.database.model.databaseprotos.InAppPaymentData;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.recipients.Recipient;

final class LogSectionBadges implements LogSection {

  @Override
  public @NonNull String getTitle() {
    return "BADGES";
  }

  @Override
  public @NonNull CharSequence getContent(@NonNull Context context) {
    if (!GenZappStore.account().isRegistered()) {
      return "Unregistered";
    }

    if (GenZappStore.account().getE164() == null || GenZappStore.account().getAci() == null) {
      return "Self not yet available!";
    }

    InAppPaymentTable.InAppPayment latestRecurringDonation = GenZappDatabase.inAppPayments().getLatestInAppPaymentByType(InAppPaymentType.RECURRING_DONATION);

    if (latestRecurringDonation != null) {
      return new StringBuilder().append("Badge Count                     : ").append(Recipient.self().getBadges().size()).append("\n")
                                .append("ExpiredBadge                    : ").append(GenZappStore.inAppPayments().getExpiredBadge() != null).append("\n")
                                .append("LastKeepAliveLaunchTime         : ").append(GenZappStore.inAppPayments().getLastKeepAliveLaunchTime()).append("\n")
                                .append("LastEndOfPeriod                 : ").append(GenZappStore.inAppPayments().getLastEndOfPeriod()).append("\n")
                                .append("InAppPayment.State              : ").append(latestRecurringDonation.getState()).append("\n")
                                .append("InAppPayment.EndOfPeriod        : ").append(latestRecurringDonation.getEndOfPeriodSeconds()).append("\n")
                                .append("InAppPaymentData.RedemptionState: ").append(getRedemptionStage(latestRecurringDonation.getData())).append("\n")
                                .append("InAppPaymentData.Error          : ").append(getError(latestRecurringDonation.getData())).append("\n")
                                .append("InAppPaymentData.Cancellation   : ").append(getCancellation(latestRecurringDonation.getData())).append("\n")
                                .append("DisplayBadgesOnProfile          : ").append(GenZappStore.inAppPayments().getDisplayBadgesOnProfile()).append("\n")
                                .append("ShouldCancelBeforeNextAttempt   : ").append(InAppPaymentsRepository.getShouldCancelSubscriptionBeforeNextSubscribeAttempt(InAppPaymentSubscriberRecord.Type.DONATION)).append("\n")
                                .append("IsUserManuallyCancelledDonation : ").append(GenZappStore.inAppPayments().isDonationSubscriptionManuallyCancelled()).append("\n");

    } else {
      return new StringBuilder().append("Badge Count                             : ").append(Recipient.self().getBadges().size()).append("\n")
                                .append("ExpiredBadge                            : ").append(GenZappStore.inAppPayments().getExpiredBadge() != null).append("\n")
                                .append("LastKeepAliveLaunchTime                 : ").append(GenZappStore.inAppPayments().getLastKeepAliveLaunchTime()).append("\n")
                                .append("LastEndOfPeriod                         : ").append(GenZappStore.inAppPayments().getLastEndOfPeriod()).append("\n")
                                .append("SubscriptionEndOfPeriodConversionStarted: ").append(GenZappStore.inAppPayments().getSubscriptionEndOfPeriodConversionStarted()).append("\n")
                                .append("SubscriptionEndOfPeriodRedemptionStarted: ").append(GenZappStore.inAppPayments().getSubscriptionEndOfPeriodRedemptionStarted()).append("\n")
                                .append("SubscriptionEndOfPeriodRedeemed         : ").append(GenZappStore.inAppPayments().getSubscriptionEndOfPeriodRedeemed()).append("\n")
                                .append("IsUserManuallyCancelledDonation         : ").append(GenZappStore.inAppPayments().isDonationSubscriptionManuallyCancelled()).append("\n")
                                .append("DisplayBadgesOnProfile                  : ").append(GenZappStore.inAppPayments().getDisplayBadgesOnProfile()).append("\n")
                                .append("SubscriptionRedemptionFailed            : ").append(GenZappStore.inAppPayments().getSubscriptionRedemptionFailed()).append("\n")
                                .append("ShouldCancelBeforeNextAttempt           : ").append(GenZappStore.inAppPayments().getShouldCancelSubscriptionBeforeNextSubscribeAttempt()).append("\n")
                                .append("Has unconverted request context         : ").append(GenZappStore.inAppPayments().getSubscriptionRequestCredential() != null).append("\n")
                                .append("Has unredeemed receipt presentation     : ").append(GenZappStore.inAppPayments().getSubscriptionReceiptCredential() != null).append("\n");
    }
  }

  private @NonNull String getRedemptionStage(@NonNull InAppPaymentData inAppPaymentData) {
    if (inAppPaymentData.redemption == null) {
      return "null";
    } else {
      return inAppPaymentData.redemption.stage.name();
    }
  }

  private @NonNull String getError(@NonNull InAppPaymentData inAppPaymentData) {
    if (inAppPaymentData.error == null) {
      return "none";
    } else {
      return inAppPaymentData.error.toString();
    }
  }

  private @NonNull String getCancellation(@NonNull InAppPaymentData inAppPaymentData) {
    if (inAppPaymentData.cancellation == null) {
      return "none";
    } else {
      return inAppPaymentData.cancellation.reason.name();
    }
  }
}
