package org.thoughtcrime.securesms.payments.preferences.addmoney

import androidx.annotation.MainThread
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.GenZapp.core.util.Result as GenZappResult

internal class PaymentsAddMoneyRepository {
  @MainThread
  fun getWalletAddress(): Single<GenZappResult<AddressAndUri, Error>> {
    if (!GenZappStore.payments.mobileCoinPaymentsEnabled()) {
      return Single.just(GenZappResult.failure(Error.PAYMENTS_NOT_ENABLED))
    }

    return Single.fromCallable<GenZappResult<AddressAndUri, Error>> {
      val publicAddress = AppDependencies.payments.wallet.mobileCoinPublicAddress
      val paymentAddressBase58 = publicAddress.paymentAddressBase58
      val paymentAddressUri = publicAddress.paymentAddressUri
      GenZappResult.success(AddressAndUri(paymentAddressBase58, paymentAddressUri))
    }
      .subscribeOn(Schedulers.io())
      .observeOn(Schedulers.io())
  }

  internal enum class Error {
    PAYMENTS_NOT_ENABLED
  }
}
