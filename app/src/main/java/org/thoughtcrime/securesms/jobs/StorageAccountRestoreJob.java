package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobmanager.JobTracker;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.profiles.manage.UsernameRepository;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.storage.StorageSyncHelper;
import org.whispersystems.GenZappservice.api.GenZappServiceAccountManager;
import org.whispersystems.GenZappservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.GenZappservice.api.storage.GenZappAccountRecord;
import org.whispersystems.GenZappservice.api.storage.GenZappStorageManifest;
import org.whispersystems.GenZappservice.api.storage.GenZappStorageRecord;
import org.whispersystems.GenZappservice.api.storage.StorageId;
import org.whispersystems.GenZappservice.api.storage.StorageKey;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Restored the AccountRecord present in the storage service, if any. This will overwrite any local
 * data that is stored in AccountRecord, so this should only be done immediately after registration.
 */
public class StorageAccountRestoreJob extends BaseJob {

  public static String KEY = "StorageAccountRestoreJob";

  public static long LIFESPAN = TimeUnit.SECONDS.toMillis(20);

  private static final String TAG = Log.tag(StorageAccountRestoreJob.class);

  public StorageAccountRestoreJob() {
    this(new Parameters.Builder()
                       .setQueue(StorageSyncJob.QUEUE_KEY)
                       .addConstraint(NetworkConstraint.KEY)
                       .setMaxInstancesForFactory(1)
                       .setMaxAttempts(1)
                       .setLifespan(LIFESPAN)
                       .build());
  }

  private StorageAccountRestoreJob(@NonNull Parameters parameters) {
    super(parameters);
  }

  @Override
  public @Nullable byte[] serialize() {
    return null;
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  protected void onRun() throws Exception {
    GenZappServiceAccountManager accountManager    = AppDependencies.getGenZappServiceAccountManager();
    StorageKey                  storageServiceKey = GenZappStore.storageService().getOrCreateStorageKey();

    Log.i(TAG, "Retrieving manifest...");
    Optional<GenZappStorageManifest> manifest = accountManager.getStorageManifest(storageServiceKey);

    if (!manifest.isPresent()) {
      Log.w(TAG, "Manifest did not exist or was undecryptable (bad key). Not restoring. Force-pushing.");
      AppDependencies.getJobManager().add(new StorageForcePushJob());
      return;
    }

    Log.i(TAG, "Resetting the local manifest to an empty state so that it will sync later.");
    GenZappStore.storageService().setManifest(GenZappStorageManifest.EMPTY);

    Optional<StorageId> accountId = manifest.get().getAccountStorageId();

    if (!accountId.isPresent()) {
      Log.w(TAG, "Manifest had no account record! Not restoring.");
      return;
    }

    Log.i(TAG, "Retrieving account record...");
    List<GenZappStorageRecord> records = accountManager.readStorageRecords(storageServiceKey, Collections.singletonList(accountId.get()));
    GenZappStorageRecord       record  = records.size() > 0 ? records.get(0) : null;

    if (record == null) {
      Log.w(TAG, "Could not find account record, even though we had an ID! Not restoring.");
      return;
    }

    GenZappAccountRecord accountRecord = record.getAccount().orElse(null);
    if (accountRecord == null) {
      Log.w(TAG, "The storage record didn't actually have an account on it! Not restoring.");
      return;
    }


    Log.i(TAG, "Applying changes locally...");
    GenZappDatabase.getRawDatabase().beginTransaction();
    try {
      StorageSyncHelper.applyAccountStorageSyncUpdates(context, Recipient.self().fresh(), accountRecord, false);
      GenZappDatabase.getRawDatabase().setTransactionSuccessful();
    } finally {
      GenZappDatabase.getRawDatabase().endTransaction();
    }

    // We will try to reclaim the username here, as early as possible, but the registration flow also enqueues a username restore job,
    // so failing here isn't a huge deal
    if (GenZappStore.account().getUsername() != null) {
      Log.i(TAG, "Attempting to reclaim username...");
      UsernameRepository.UsernameReclaimResult result = UsernameRepository.reclaimUsernameIfNecessary();
      Log.i(TAG, "Username reclaim result: " + result.name());
    } else {
      Log.i(TAG, "No username to reclaim.");
    }

    JobManager jobManager = AppDependencies.getJobManager();

    if (accountRecord.getAvatarUrlPath().isPresent()) {
      Log.i(TAG,  "Fetching avatar...");
      Optional<JobTracker.JobState> state = jobManager.runSynchronously(new RetrieveProfileAvatarJob(Recipient.self(), accountRecord.getAvatarUrlPath().get()), LIFESPAN/2);

      if (state.isPresent()) {
        Log.i(TAG, "Avatar retrieved successfully. " + state.get());
      } else {
        Log.w(TAG, "Avatar retrieval did not complete in time (or otherwise failed).");
      }
    } else {
      Log.i(TAG, "No avatar present. Not fetching.");
    }

    Log.i(TAG,  "Refreshing attributes...");
    Optional<JobTracker.JobState> state = jobManager.runSynchronously(new RefreshAttributesJob(), LIFESPAN/2);

    if (state.isPresent()) {
      Log.i(TAG, "Attributes refreshed successfully. " + state.get());
    } else {
      Log.w(TAG, "Attribute refresh did not complete in time (or otherwise failed).");
    }
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception e) {
    return e instanceof PushNetworkException;
  }

  @Override
  public void onFailure() {
  }

  public static class Factory implements Job.Factory<StorageAccountRestoreJob> {
    @Override
    public @NonNull
    StorageAccountRestoreJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      return new StorageAccountRestoreJob(parameters);
    }
  }
}
