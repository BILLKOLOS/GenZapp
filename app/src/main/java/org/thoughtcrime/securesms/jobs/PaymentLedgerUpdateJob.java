package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.mobilecoin.lib.exceptions.FogSyncException;

import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.database.PaymentTable;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobmanager.JsonJobData;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.payments.MobileCoinLedgerWrapper;
import org.thoughtcrime.securesms.transport.RetryLaterException;

import java.io.IOException;
import java.util.UUID;

/**
 * Updates the local cache of the ledger, and so the balance.
 */
public final class PaymentLedgerUpdateJob extends BaseJob {

  private static final String TAG = Log.tag(PaymentLedgerUpdateJob.class);

  public static final String KEY = "PaymentLedgerUpdateJob";

  private static final String KEY_PAYMENT_UUID = "payment_uuid";

  private final UUID paymentUuid;

  public static PaymentLedgerUpdateJob updateLedgerToReflectPayment(@NonNull UUID paymentUuid) {
    return new PaymentLedgerUpdateJob(paymentUuid);
  }

  public static PaymentLedgerUpdateJob updateLedger() {
    return new PaymentLedgerUpdateJob(null);
  }

  private PaymentLedgerUpdateJob(@Nullable UUID paymentUuid) {
    this(new Parameters.Builder()
                 .setQueue(PaymentSendJob.QUEUE)
                 .setMaxAttempts(10)
                 .setMaxInstancesForQueue(1)
                 .build(),
         paymentUuid);
  }

  private PaymentLedgerUpdateJob(@NonNull Parameters parameters,
                                 @Nullable UUID paymentUuid)
  {
    super(parameters);
    this.paymentUuid = paymentUuid;
  }

  @Override
  protected void onRun() throws IOException, RetryLaterException, FogSyncException {
    if (!GenZappStore.payments().mobileCoinPaymentsEnabled()) {
      Log.w(TAG, "Payments are not enabled");
      return;
    }

    Long minimumBlockIndex = null;
    if (paymentUuid != null) {
      PaymentTable.PaymentTransaction payment = GenZappDatabase.payments()
                                                              .getPayment(paymentUuid);

      if (payment != null) {
        minimumBlockIndex = payment.getBlockIndex();
        Log.i(TAG, "Fetching for a payment " + paymentUuid + " " + (minimumBlockIndex > 0 ? "non-zero" : "zero"));
      } else {
        Log.w(TAG, "Payment not found " + paymentUuid);
      }
    }

    MobileCoinLedgerWrapper ledger = AppDependencies.getPayments()
                                                    .getWallet()
                                                    .tryGetFullLedger(minimumBlockIndex);

    if (ledger == null) {
      Log.i(TAG, "Ledger not updated yet, waiting for a minimum block index");
      throw new RetryLaterException();
    }

    Log.i(TAG, "Ledger fetched successfully");

    GenZappStore.payments()
               .setMobileCoinFullLedger(ledger);
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception e) {
    return true;
  }

  @Override
  public @Nullable byte[] serialize() {
    return new JsonJobData.Builder()
                   .putString(KEY_PAYMENT_UUID, paymentUuid != null ? paymentUuid.toString() : null)
                   .serialize();
  }

  @NonNull @Override
  public String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onFailure() {
    Log.w(TAG, "Failed to get ledger");
  }

  public static final class Factory implements Job.Factory<PaymentLedgerUpdateJob> {
    @Override
    public @NonNull PaymentLedgerUpdateJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      JsonJobData data = JsonJobData.deserialize(serializedData);

      String paymentUuid = data.getString(KEY_PAYMENT_UUID);
      return new PaymentLedgerUpdateJob(parameters,
                                        paymentUuid != null ? UUID.fromString(paymentUuid) : null);
    }
  }
}
