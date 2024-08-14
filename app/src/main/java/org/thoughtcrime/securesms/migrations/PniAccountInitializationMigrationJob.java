package org.thoughtcrime.securesms.migrations;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.GenZapp.core.util.logging.Log;
import org.GenZapp.libGenZapp.protocol.state.PreKeyRecord;
import org.GenZapp.libGenZapp.protocol.state.SignedPreKeyRecord;
import org.thoughtcrime.securesms.crypto.PreKeyUtil;
import org.thoughtcrime.securesms.crypto.storage.PreKeyMetadataStore;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.GenZappservice.api.GenZappServiceAccountDataStore;
import org.whispersystems.GenZappservice.api.GenZappServiceAccountManager;
import org.whispersystems.GenZappservice.api.account.PreKeyUpload;
import org.whispersystems.GenZappservice.api.push.ServiceId.PNI;
import org.whispersystems.GenZappservice.api.push.ServiceIdType;

import java.io.IOException;
import java.util.List;

/**
 * Initializes various aspects of the PNI identity. Notably:
 * - Creates an identity key
 * - Creates and uploads one-time prekeys
 * - Creates and uploads signed prekeys
 */
public class PniAccountInitializationMigrationJob extends MigrationJob {

  private static final String TAG = Log.tag(PniAccountInitializationMigrationJob.class);

  public static final String KEY = "PniAccountInitializationMigrationJob";

  PniAccountInitializationMigrationJob() {
    this(new Parameters.Builder()
                       .addConstraint(NetworkConstraint.KEY)
                       .build());
  }

  private PniAccountInitializationMigrationJob(@NonNull Parameters parameters) {
    super(parameters);
  }

  @Override
  public boolean isUiBlocking() {
    return false;
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void performMigration() throws IOException {
    PNI pni = GenZappStore.account().getPni();

    if (pni == null || GenZappStore.account().getAci() == null || !Recipient.self().isRegistered()) {
      Log.w(TAG, "Not yet registered! No need to perform this migration.");
      return;
    }

    if (!GenZappStore.account().hasPniIdentityKey()) {
      Log.i(TAG, "Generating PNI identity.");
      GenZappStore.account().generatePniIdentityKeyIfNecessary();
    } else {
      Log.w(TAG, "Already generated the PNI identity. Skipping this step.");
    }

    GenZappServiceAccountManager   accountManager = AppDependencies.getGenZappServiceAccountManager();
    GenZappServiceAccountDataStore protocolStore  = AppDependencies.getProtocolStore().pni();
    PreKeyMetadataStore           metadataStore  = GenZappStore.account().pniPreKeys();

    if (!metadataStore.isSignedPreKeyRegistered()) {
      Log.i(TAG, "Uploading signed prekey for PNI.");
      SignedPreKeyRecord signedPreKey   = PreKeyUtil.generateAndStoreSignedPreKey(protocolStore, metadataStore);
      List<PreKeyRecord> oneTimePreKeys = PreKeyUtil.generateAndStoreOneTimeEcPreKeys(protocolStore, metadataStore);

      accountManager.setPreKeys(new PreKeyUpload(ServiceIdType.PNI, signedPreKey, oneTimePreKeys, null, null));
      metadataStore.setActiveSignedPreKeyId(signedPreKey.getId());
      metadataStore.setSignedPreKeyRegistered(true);
    } else {
      Log.w(TAG, "Already uploaded signed prekey for PNI. Skipping this step.");
    }
  }

  @Override
  boolean shouldRetry(@NonNull Exception e) {
    return e instanceof IOException;
  }

  public static class Factory implements Job.Factory<PniAccountInitializationMigrationJob> {
    @Override
    public @NonNull PniAccountInitializationMigrationJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      return new PniAccountInitializationMigrationJob(parameters);
    }
  }
}
