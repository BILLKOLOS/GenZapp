/*
 * Copyright 2024 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.badges.self.expired

import androidx.annotation.StringRes
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.GenZapp.donations.InAppPaymentType
import org.GenZapp.donations.StripeDeclineCode
import org.GenZapp.donations.StripeFailureCode
import org.thoughtcrime.securesms.badges.Badges
import org.thoughtcrime.securesms.components.settings.app.subscription.InAppPaymentsRepository.toActiveSubscriptionChargeFailure
import org.thoughtcrime.securesms.components.settings.app.subscription.errors.mapToErrorStringResource
import org.thoughtcrime.securesms.database.InAppPaymentTable
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.whispersystems.GenZappservice.api.subscriptions.ActiveSubscription.ChargeFailure

class MonthlyDonationCanceledViewModel(
  inAppPaymentId: InAppPaymentTable.InAppPaymentId?
) : ViewModel() {
  private val internalState = mutableStateOf(MonthlyDonationCanceledState())
  val state: State<MonthlyDonationCanceledState> = internalState

  init {
    if (inAppPaymentId != null) {
      initializeFromInAppPaymentId(inAppPaymentId)
    } else {
      initializeFromGenZappStore()
    }
  }

  private fun initializeFromInAppPaymentId(inAppPaymentId: InAppPaymentTable.InAppPaymentId) {
    viewModelScope.launch {
      val inAppPayment = withContext(Dispatchers.IO) {
        GenZappDatabase.inAppPayments.getById(inAppPaymentId)
      }

      if (inAppPayment != null) {
        internalState.value = MonthlyDonationCanceledState(
          loadState = MonthlyDonationCanceledState.LoadState.READY,
          badge = Badges.fromDatabaseBadge(inAppPayment.data.badge!!),
          errorMessage = getErrorMessage(inAppPayment.data.cancellation?.chargeFailure?.toActiveSubscriptionChargeFailure())
        )
      } else {
        internalState.value = internalState.value.copy(loadState = MonthlyDonationCanceledState.LoadState.FAILED)
      }
    }
  }

  private fun initializeFromGenZappStore() {
    internalState.value = MonthlyDonationCanceledState(
      loadState = MonthlyDonationCanceledState.LoadState.READY,
      badge = GenZappStore.inAppPayments.getExpiredBadge(),
      errorMessage = getErrorMessage(GenZappStore.inAppPayments.getUnexpectedSubscriptionCancelationChargeFailure())
    )
  }

  @StringRes
  private fun getErrorMessage(chargeFailure: ChargeFailure?): Int {
    val declineCode: StripeDeclineCode = StripeDeclineCode.getFromCode(chargeFailure?.outcomeNetworkReason)
    val failureCode: StripeFailureCode = StripeFailureCode.getFromCode(chargeFailure?.code)

    return if (declineCode.isKnown()) {
      declineCode.mapToErrorStringResource()
    } else if (failureCode.isKnown) {
      failureCode.mapToErrorStringResource(InAppPaymentType.RECURRING_DONATION)
    } else {
      declineCode.mapToErrorStringResource()
    }
  }
}
