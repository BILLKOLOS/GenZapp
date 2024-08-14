package org.thoughtcrime.securesms.badges.gifts.flow

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.subjects.PublishSubject
import org.GenZapp.core.util.logging.Log
import org.GenZapp.core.util.money.FiatMoney
import org.thoughtcrime.securesms.badges.models.Badge
import org.thoughtcrime.securesms.components.settings.app.subscription.DonationEvent
import org.thoughtcrime.securesms.contacts.paged.ContactSearchKey
import org.thoughtcrime.securesms.database.InAppPaymentTable
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.InternetConnectionObserver
import org.thoughtcrime.securesms.util.rx.RxStore
import java.util.Currency

/**
 * Maintains state as a user works their way through the gift flow.
 */
class GiftFlowViewModel(
  private val giftFlowRepository: GiftFlowRepository
) : ViewModel() {

  private val store = RxStore(
    GiftFlowState(
      currency = GenZappStore.inAppPayments.getOneTimeCurrency()
    )
  )
  private val disposables = CompositeDisposable()
  private val eventPublisher: PublishSubject<DonationEvent> = PublishSubject.create()
  private val networkDisposable: Disposable

  val state: Flowable<GiftFlowState> = store.stateFlowable
  val events: Observable<DonationEvent> = eventPublisher
  val snapshot: GiftFlowState get() = store.state

  init {
    refresh()

    networkDisposable = InternetConnectionObserver
      .observe()
      .distinctUntilChanged()
      .subscribe { isConnected ->
        if (isConnected) {
          retry()
        }
      }
  }

  fun retry() {
    if (!disposables.isDisposed && store.state.stage == GiftFlowState.Stage.FAILURE) {
      store.update { it.copy(stage = GiftFlowState.Stage.INIT) }
      refresh()
    }
  }

  fun refresh() {
    disposables.clear()
    disposables += GenZappStore.inAppPayments.observableOneTimeCurrency.subscribe { currency ->
      store.update {
        it.copy(
          currency = currency
        )
      }
    }

    disposables += giftFlowRepository.getGiftPricing().subscribe { giftPrices ->
      store.update {
        it.copy(
          giftPrices = giftPrices,
          stage = getLoadState(it, giftPrices = giftPrices)
        )
      }
    }

    disposables += giftFlowRepository.getGiftBadge().subscribeBy(
      onSuccess = { (giftLevel, giftBadge) ->
        store.update {
          it.copy(
            giftLevel = giftLevel.toLong(),
            giftBadge = giftBadge,
            stage = getLoadState(it, giftBadge = giftBadge)
          )
        }
      },
      onError = { throwable ->
        Log.w(TAG, "Could not load gift badge", throwable)
        store.update {
          it.copy(
            stage = GiftFlowState.Stage.FAILURE
          )
        }
      }
    )
  }

  fun insertInAppPayment(context: Context): Single<InAppPaymentTable.InAppPayment> {
    val giftSnapshot = snapshot
    return giftFlowRepository.insertInAppPayment(context, giftSnapshot)
      .doOnSuccess { inAppPayment ->
        store.update { it.copy(inAppPaymentId = inAppPayment.id) }
      }
      .observeOn(AndroidSchedulers.mainThread())
  }

  override fun onCleared() {
    disposables.clear()
  }

  fun setSelectedContact(selectedContact: ContactSearchKey.RecipientSearchKey) {
    store.update {
      it.copy(recipient = Recipient.resolved(selectedContact.recipientId))
    }
  }

  fun getSupportedCurrencyCodes(): List<String> {
    return store.state.giftPrices.keys.map { it.currencyCode }
  }

  private fun getLoadState(
    oldState: GiftFlowState,
    giftPrices: Map<Currency, FiatMoney>? = null,
    giftBadge: Badge? = null
  ): GiftFlowState.Stage {
    if (oldState.stage != GiftFlowState.Stage.INIT) {
      return oldState.stage
    }

    if (giftPrices?.isNotEmpty() == true) {
      return if (oldState.giftBadge != null) {
        GiftFlowState.Stage.READY
      } else {
        GiftFlowState.Stage.INIT
      }
    }

    if (giftBadge != null) {
      return if (oldState.giftPrices.isNotEmpty()) {
        GiftFlowState.Stage.READY
      } else {
        GiftFlowState.Stage.INIT
      }
    }

    return GiftFlowState.Stage.INIT
  }

  fun setAdditionalMessage(additionalMessage: CharSequence) {
    store.update { it.copy(additionalMessage = additionalMessage) }
  }

  companion object {
    private val TAG = Log.tag(GiftFlowViewModel::class.java)
  }

  class Factory(
    private val repository: GiftFlowRepository
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return modelClass.cast(
        GiftFlowViewModel(
          repository
        )
      ) as T
    }
  }
}
