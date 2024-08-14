package org.thoughtcrime.securesms.crypto.storage;

import android.content.Context;

import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.GenZappservice.api.GenZappServiceDataStore;
import org.whispersystems.GenZappservice.api.push.ServiceId;

public final class GenZappServiceDataStoreImpl implements GenZappServiceDataStore {

  private final Context                           context;
  private final GenZappServiceAccountDataStoreImpl aciStore;
  private final GenZappServiceAccountDataStoreImpl pniStore;

  public GenZappServiceDataStoreImpl(@NonNull Context context,
                                    @NonNull GenZappServiceAccountDataStoreImpl aciStore,
                                    @NonNull GenZappServiceAccountDataStoreImpl pniStore)
  {
    this.context  = context;
    this.aciStore = aciStore;
    this.pniStore = pniStore;
  }

  @Override
  public GenZappServiceAccountDataStoreImpl get(@NonNull ServiceId accountIdentifier) {
    if (accountIdentifier.equals(GenZappStore.account().getAci())) {
      return aciStore;
    } else if (accountIdentifier.equals(GenZappStore.account().getPni())) {
      return pniStore;
    } else {
      throw new IllegalArgumentException("No matching store found for " + accountIdentifier);
    }
  }

  @Override
  public GenZappServiceAccountDataStoreImpl aci() {
    return aciStore;
  }

  @Override
  public GenZappServiceAccountDataStoreImpl pni() {
    return pniStore;
  }

  @Override
  public boolean isMultiDevice() {
    return TextSecurePreferences.isMultiDevice(context);
  }
}
