package org.thoughtcrime.securesms.migrations;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.whispersystems.GenZappservice.api.push.ServiceId.PNI;

import java.io.IOException;

/**
 * Migration to fetch our own PNI from the service.
 */
public class PniMigrationJob extends MigrationJob {

  public static final String KEY = "PniMigrationJob";

  private static final String TAG = Log.tag(PniMigrationJob.class);

  PniMigrationJob() {
    this(new Parameters.Builder().addConstraint(NetworkConstraint.KEY).build());
  }

  private PniMigrationJob(@NonNull Parameters parameters) {
    super(parameters);
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  boolean isUiBlocking() {
    return false;
  }

  @Override
  void performMigration() throws Exception {
    if (!GenZappStore.account().isRegistered() || GenZappStore.account().getAci() == null) {
      Log.w(TAG, "Not registered! Skipping migration, as it wouldn't do anything.");
      return;
    }

    PNI pni = PNI.parseOrNull(AppDependencies.getGenZappServiceAccountManager().getWhoAmI().getPni());

    if (pni == null) {
      throw new IOException("Invalid PNI!");
    }

    GenZappDatabase.recipients().linkIdsForSelf(GenZappStore.account().requireAci(), pni, GenZappStore.account().requireE164());
    GenZappStore.account().setPni(pni);
  }

  @Override
  boolean shouldRetry(@NonNull Exception e) {
    return e instanceof IOException;
  }

  public static class Factory implements Job.Factory<PniMigrationJob> {
    @Override
    public @NonNull PniMigrationJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      return new PniMigrationJob(parameters);
    }
  }
}
