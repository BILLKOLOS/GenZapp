package org.thoughtcrime.securesms.payments.backup;

import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.payments.Mnemonic;

public final class PaymentsRecoveryRepository {
  public @NonNull Mnemonic getMnemonic() {
    return GenZappStore.payments().getPaymentsMnemonic();
  }
}
