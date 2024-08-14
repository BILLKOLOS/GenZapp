package org.thoughtcrime.securesms.components.settings.app.internal.donor

import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.schedulers.Schedulers
import org.GenZapp.donations.StripeDeclineCode
import org.thoughtcrime.securesms.badges.Badges
import org.thoughtcrime.securesms.badges.models.Badge
import org.thoughtcrime.securesms.components.settings.app.subscription.errors.UnexpectedSubscriptionCancellation
import org.thoughtcrime.securesms.components.settings.app.subscription.getBoostBadges
import org.thoughtcrime.securesms.components.settings.app.subscription.getGiftBadges
import org.thoughtcrime.securesms.components.settings.app.subscription.getSubscriptionLevels
import org.thoughtcrime.securesms.database.model.InAppPaymentSubscriberRecord
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.util.rx.RxStore
import org.whispersystems.GenZappservice.api.subscriptions.ActiveSubscription
import java.util.Locale

class InternalDonorErrorConfigurationViewModel : ViewModel() {

  private val store = RxStore(InternalDonorErrorConfigurationState())
  private val disposables = CompositeDisposable()

  val state: Flowable<InternalDonorErrorConfigurationState> = store.stateFlowable

  init {
    val giftBadges: Single<List<Badge>> = Single
      .fromCallable {
        AppDependencies.donationsService
          .getDonationsConfiguration(Locale.getDefault())
      }
      .flatMap { it.flattenResult() }
      .map { it.getGiftBadges() }
      .subscribeOn(Schedulers.io())

    val boostBadges: Single<List<Badge>> = Single
      .fromCallable {
        AppDependencies.donationsService
          .getDonationsConfiguration(Locale.getDefault())
      }
      .flatMap { it.flattenResult() }
      .map { it.getBoostBadges() }
      .subscribeOn(Schedulers.io())

    val subscriptionBadges: Single<List<Badge>> = Single
      .fromCallable {
        AppDependencies.donationsService
          .getDonationsConfiguration(Locale.getDefault())
      }
      .flatMap { it.flattenResult() }
      .map { config -> config.getSubscriptionLevels().values.map { Badges.fromServiceBadge(it.badge) } }
      .subscribeOn(Schedulers.io())

    disposables += Single.zip(giftBadges, boostBadges, subscriptionBadges) { g, b, s ->
      g + b + s
    }.subscribe { badges ->
      store.update { it.copy(badges = badges) }
    }
  }

  override fun onCleared() {
    disposables.clear()
    store.dispose()
  }

  fun setSelectedBadge(badgeIndex: Int) {
    store.update {
      it.copy(selectedBadge = if (badgeIndex in it.badges.indices) it.badges[badgeIndex] else null)
    }
  }

  fun setSelectedUnexpectedSubscriptionCancellation(unexpectedSubscriptionCancellationIndex: Int) {
    store.update {
      it.copy(
        selectedUnexpectedSubscriptionCancellation = if (unexpectedSubscriptionCancellationIndex in UnexpectedSubscriptionCancellation.values().indices) {
          UnexpectedSubscriptionCancellation.values()[unexpectedSubscriptionCancellationIndex]
        } else {
          null
        }
      )
    }
  }

  fun setStripeDeclineCode(stripeDeclineCodeIndex: Int) {
    store.update {
      it.copy(
        selectedStripeDeclineCode = if (stripeDeclineCodeIndex in StripeDeclineCode.Code.values().indices) {
          StripeDeclineCode.Code.values()[stripeDeclineCodeIndex]
        } else {
          null
        }
      )
    }
  }

  fun save(): Completable {
    val snapshot = store.state
    val saveState = Completable.fromAction {
      synchronized(InAppPaymentSubscriberRecord.Type.DONATION) {
        when {
          snapshot.selectedBadge?.isGift() == true -> handleGiftExpiration(snapshot)
          snapshot.selectedBadge?.isBoost() == true -> handleBoostExpiration(snapshot)
          snapshot.selectedBadge?.isSubscription() == true -> handleSubscriptionExpiration(snapshot)
          else -> handleSubscriptionPaymentFailure(snapshot)
        }
      }
    }.subscribeOn(Schedulers.io())

    return clearErrorState().andThen(saveState)
  }

  fun clearErrorState(): Completable {
    return Completable.fromAction {
      synchronized(InAppPaymentSubscriberRecord.Type.DONATION) {
        GenZappStore.inAppPayments.setExpiredBadge(null)
        GenZappStore.inAppPayments.setExpiredGiftBadge(null)
        GenZappStore.inAppPayments.unexpectedSubscriptionCancelationReason = null
        GenZappStore.inAppPayments.unexpectedSubscriptionCancelationTimestamp = 0L
        GenZappStore.inAppPayments.setUnexpectedSubscriptionCancelationChargeFailure(null)
      }

      store.update {
        it.copy(
          selectedStripeDeclineCode = null,
          selectedUnexpectedSubscriptionCancellation = null,
          selectedBadge = null
        )
      }
    }
  }

  private fun handleBoostExpiration(state: InternalDonorErrorConfigurationState) {
    GenZappStore.inAppPayments.setExpiredBadge(state.selectedBadge)
  }

  private fun handleGiftExpiration(state: InternalDonorErrorConfigurationState) {
    GenZappStore.inAppPayments.setExpiredGiftBadge(state.selectedBadge)
  }

  private fun handleSubscriptionExpiration(state: InternalDonorErrorConfigurationState) {
    GenZappStore.inAppPayments.updateLocalStateForLocalSubscribe(InAppPaymentSubscriberRecord.Type.DONATION)
    GenZappStore.inAppPayments.setExpiredBadge(state.selectedBadge)
    handleSubscriptionPaymentFailure(state)
  }

  private fun handleSubscriptionPaymentFailure(state: InternalDonorErrorConfigurationState) {
    GenZappStore.inAppPayments.unexpectedSubscriptionCancelationReason = state.selectedUnexpectedSubscriptionCancellation?.status
    GenZappStore.inAppPayments.unexpectedSubscriptionCancelationTimestamp = System.currentTimeMillis()
    GenZappStore.inAppPayments.showMonthlyDonationCanceledDialog = true
    GenZappStore.inAppPayments.setUnexpectedSubscriptionCancelationChargeFailure(
      state.selectedStripeDeclineCode?.let {
        ActiveSubscription.ChargeFailure(
          it.code,
          "Test Charge Failure",
          "Test Network Status",
          it.code,
          "Test"
        )
      }
    )
  }
}
