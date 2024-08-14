package org.thoughtcrime.securesms.components.settings.app.subscription.receipts.list

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.database.model.InAppPaymentReceiptRecord

class DonationReceiptListPageRepository {
  fun getRecords(type: InAppPaymentReceiptRecord.Type?): Single<List<InAppPaymentReceiptRecord>> {
    return Single.fromCallable {
      GenZappDatabase.donationReceipts.getReceipts(type)
    }.subscribeOn(Schedulers.io())
  }
}
