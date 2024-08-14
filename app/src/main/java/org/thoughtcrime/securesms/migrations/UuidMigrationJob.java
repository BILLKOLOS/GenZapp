package org.thoughtcrime.securesms.migrations;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.whispersystems.GenZappservice.api.push.ServiceId.ACI;

import java.io.IOException;
import java.util.Objects;

/**
 * Couple migrations steps need to happen after we move to UUIDS.
 *  - We need to get our own UUID.
 *  - We need to fetch the new UUID sealed sender cert.
 *  - We need to do a directory sync so we can guarantee that all active users have UUIDs.
 */
public class UuidMigrationJob extends MigrationJob {

  public static final String KEY = "UuidMigrationJob";

  private static final String TAG = Log.tag(UuidMigrationJob.class);

  UuidMigrationJob() {
    this(new Parameters.Builder().addConstraint(NetworkConstraint.KEY).build());
  }

  private UuidMigrationJob(@NonNull Parameters parameters) {
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
    if (!GenZappStore.account().isRegistered() || TextUtils.isEmpty(GenZappStore.account().getE164())) {
      Log.w(TAG, "Not registered! Skipping migration, as it wouldn't do anything.");
      return;
    }

    ensureSelfRecipientExists(context);
    fetchOwnUuid(context);
  }

  @Override
  boolean shouldRetry(@NonNull Exception e) {
    return e instanceof IOException;
  }

  private static void ensureSelfRecipientExists(@NonNull Context context) {
    GenZappDatabase.recipients().getOrInsertFromE164(Objects.requireNonNull(GenZappStore.account().getE164()));
  }

  private static void fetchOwnUuid(@NonNull Context context) throws IOException {
    RecipientId self      = Recipient.self().getId();
    ACI         localUuid = ACI.parseOrNull(AppDependencies.getGenZappServiceAccountManager().getWhoAmI().getAci());

    if (localUuid == null) {
      throw new IOException("Invalid UUID!");
    }

    GenZappDatabase.recipients().markRegisteredOrThrow(self, localUuid);
    GenZappStore.account().setAci(localUuid);
  }

  public static class Factory implements Job.Factory<UuidMigrationJob> {
    @Override
    public @NonNull UuidMigrationJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      return new UuidMigrationJob(parameters);
    }
  }
}
