package org.thoughtcrime.securesms.migrations

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.GenZapp.core.util.count
import org.GenZapp.core.util.readToSingleInt
import org.GenZapp.donations.PaymentSourceType
import org.thoughtcrime.securesms.database.InAppPaymentSubscriberTable
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.database.model.InAppPaymentSubscriberRecord
import org.thoughtcrime.securesms.database.model.databaseprotos.InAppPaymentData
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.testing.assertIs
import org.thoughtcrime.securesms.testing.assertIsNotNull
import org.whispersystems.GenZappservice.api.subscriptions.SubscriberId
import java.util.Currency

@RunWith(AndroidJUnit4::class)
class SubscriberIdMigrationJobTest {

  private val testSubject = SubscriberIdMigrationJob()

  @Test
  fun givenNoSubscriber_whenIRunSubscriberIdMigrationJob_thenIExpectNoDatabaseEntries() {
    testSubject.run()

    val actual = GenZappDatabase.inAppPaymentSubscribers.readableDatabase.count()
      .from(InAppPaymentSubscriberTable.TABLE_NAME)
      .run()
      .readToSingleInt()

    actual assertIs 0
  }

  @Test
  fun givenUSDSubscriber_whenIRunSubscriberIdMigrationJob_thenIExpectASingleEntry() {
    val subscriberId = SubscriberId.generate()
    GenZappStore.inAppPayments.setSubscriberCurrency(Currency.getInstance("USD"), InAppPaymentSubscriberRecord.Type.DONATION)
    GenZappStore.inAppPayments.setSubscriber("USD", subscriberId)
    GenZappStore.inAppPayments.setSubscriptionPaymentSourceType(PaymentSourceType.PayPal)
    GenZappStore.inAppPayments.shouldCancelSubscriptionBeforeNextSubscribeAttempt = true

    testSubject.run()

    val actual = GenZappDatabase.inAppPaymentSubscribers.getByCurrencyCode("USD", InAppPaymentSubscriberRecord.Type.DONATION)

    actual.assertIsNotNull()
    actual!!.subscriberId.bytes assertIs subscriberId.bytes
    actual.paymentMethodType assertIs InAppPaymentData.PaymentMethodType.PAYPAL
    actual.requiresCancel assertIs true
    actual.currency assertIs Currency.getInstance("USD")
    actual.type assertIs InAppPaymentSubscriberRecord.Type.DONATION
  }
}
