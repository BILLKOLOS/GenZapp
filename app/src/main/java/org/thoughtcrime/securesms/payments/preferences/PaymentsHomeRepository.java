package org.thoughtcrime.securesms.payments.preferences;

import androidx.annotation.NonNull;
import androidx.core.util.Consumer;

import org.GenZapp.core.util.concurrent.GenZappExecutors;
import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobs.PaymentLedgerUpdateJob;
import org.thoughtcrime.securesms.jobs.ProfileUploadJob;
import org.thoughtcrime.securesms.jobs.SendPaymentsActivatedJob;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.util.AsynchronousCallback;
import org.thoughtcrime.securesms.util.ProfileUtil;
import org.whispersystems.GenZappservice.api.push.exceptions.NonSuccessfulResponseCodeException;
import org.whispersystems.GenZappservice.internal.push.exceptions.PaymentsRegionException;

import java.io.IOException;

public class PaymentsHomeRepository {

  private static final String TAG = Log.tag(PaymentsHomeRepository.class);

  public void activatePayments(@NonNull AsynchronousCallback.WorkerThread<Void, Error> callback) {
    GenZappExecutors.BOUNDED.execute(() -> {
      GenZappStore.payments().setMobileCoinPaymentsEnabled(true);
      try {
        ProfileUtil.uploadProfile(AppDependencies.getApplication());
        AppDependencies.getJobManager()
                       .startChain(PaymentLedgerUpdateJob.updateLedger())
                       .then(new SendPaymentsActivatedJob())
                       .enqueue();
        callback.onComplete(null);
      } catch (PaymentsRegionException e) {
        GenZappStore.payments().setMobileCoinPaymentsEnabled(false);
        Log.w(TAG, "Problem enabling payments in region", e);
        callback.onError(Error.RegionError);
      } catch (NonSuccessfulResponseCodeException e) {
        GenZappStore.payments().setMobileCoinPaymentsEnabled(false);
        Log.w(TAG, "Problem enabling payments", e);
        callback.onError(Error.NetworkError);
      } catch (IOException e) {
        GenZappStore.payments().setMobileCoinPaymentsEnabled(false);
        Log.w(TAG, "Problem enabling payments", e);
        tryToRestoreProfile();
        callback.onError(Error.NetworkError);
      }
    });
  }

  private void tryToRestoreProfile() {
    try {
      ProfileUtil.uploadProfile(AppDependencies.getApplication());
      Log.i(TAG, "Restored profile");
    } catch (IOException e) {
      Log.w(TAG, "Problem uploading profile", e);
    }
  }

  public void deactivatePayments(@NonNull Consumer<Boolean> consumer) {
    GenZappExecutors.BOUNDED.execute(() -> {
      GenZappStore.payments().setMobileCoinPaymentsEnabled(false);
      AppDependencies.getJobManager().add(new ProfileUploadJob());
      consumer.accept(!GenZappStore.payments().mobileCoinPaymentsEnabled());
    });
  }

  public enum Error {
    NetworkError,
    RegionError
  }
}
