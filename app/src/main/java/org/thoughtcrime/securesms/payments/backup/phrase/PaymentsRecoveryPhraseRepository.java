package org.thoughtcrime.securesms.payments.backup.phrase;

import androidx.annotation.NonNull;
import androidx.core.util.Consumer;

import org.GenZapp.core.util.concurrent.GenZappExecutors;
import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobs.PaymentLedgerUpdateJob;
import org.thoughtcrime.securesms.jobs.ProfileUploadJob;
import org.thoughtcrime.securesms.keyvalue.PaymentsValues;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.util.Util;

import java.util.List;

class PaymentsRecoveryPhraseRepository {

  private static final String TAG = Log.tag(PaymentsRecoveryPhraseRepository.class);

  void restoreMnemonic(@NonNull List<String> words,
                       @NonNull Consumer<PaymentsValues.WalletRestoreResult> resultConsumer)
  {
    GenZappExecutors.BOUNDED.execute(() -> {
      String                             mnemonic = Util.join(words, " ");
      PaymentsValues.WalletRestoreResult result   = GenZappStore.payments().restoreWallet(mnemonic);

      switch (result) {
        case ENTROPY_CHANGED:
          Log.i(TAG, "restoreMnemonic: mnemonic resulted in entropy mismatch, flushing cached values");
          GenZappDatabase.payments().deleteAll();
          AppDependencies.getPayments().closeWallet();
          updateProfileAndFetchLedger();
          break;
        case ENTROPY_UNCHANGED:
          Log.i(TAG, "restoreMnemonic: mnemonic resulted in entropy match, no flush needed.");
          updateProfileAndFetchLedger();
          break;
        case MNEMONIC_ERROR:
          Log.w(TAG, "restoreMnemonic: failed to restore wallet from given mnemonic.");
          break;
      }

      resultConsumer.accept(result);
    });
  }

  private void updateProfileAndFetchLedger() {
    AppDependencies.getJobManager()
                   .startChain(new ProfileUploadJob())
                   .then(PaymentLedgerUpdateJob.updateLedger())
                   .enqueue();
  }
}
