package org.thoughtcrime.securesms.components.settings.app.subscription.donate

import org.GenZapp.donations.InAppPaymentType
import org.thoughtcrime.securesms.database.InAppPaymentTable

sealed class DonateToGenZappAction {
  data class DisplayCurrencySelectionDialog(val inAppPaymentType: InAppPaymentType, val supportedCurrencies: List<String>) : DonateToGenZappAction()
  data class DisplayGatewaySelectorDialog(val inAppPayment: InAppPaymentTable.InAppPayment) : DonateToGenZappAction()
  data object CancelSubscription : DonateToGenZappAction()
  data class UpdateSubscription(val inAppPayment: InAppPaymentTable.InAppPayment, val isLongRunning: Boolean) : DonateToGenZappAction()
}
