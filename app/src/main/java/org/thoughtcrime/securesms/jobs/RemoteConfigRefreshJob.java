package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.util.RemoteConfig;
import org.whispersystems.GenZappservice.api.RemoteConfigResult;
import org.whispersystems.GenZappservice.api.push.exceptions.PushNetworkException;

import java.util.concurrent.TimeUnit;

public class RemoteConfigRefreshJob extends BaseJob {

  private static final String TAG = Log.tag(RemoteConfigRefreshJob.class);

  public static final String KEY = "RemoteConfigRefreshJob";

  public RemoteConfigRefreshJob() {
    this(new Job.Parameters.Builder()
                           .setQueue("RemoteConfigRefreshJob")
                           .setMaxInstancesForFactory(1)
                           .addConstraint(NetworkConstraint.KEY)
                           .setMaxAttempts(Parameters.UNLIMITED)
                           .setLifespan(TimeUnit.DAYS.toMillis(1))
                           .build());
  }

  private RemoteConfigRefreshJob(@NonNull Parameters parameters) {
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
    if (!GenZappStore.account().isRegistered()) {
      Log.w(TAG, "Not registered. Skipping.");
      return;
    }

    RemoteConfigResult result = AppDependencies.getGenZappServiceAccountManager().getRemoteConfig();
    RemoteConfig.update(result.getConfig());
    GenZappStore.misc().setLastKnownServerTime(TimeUnit.SECONDS.toMillis(result.getServerEpochTimeSeconds()), System.currentTimeMillis());
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception e) {
    return e instanceof PushNetworkException;
  }

  @Override
  public void onFailure() {
  }

  public static final class Factory implements Job.Factory<RemoteConfigRefreshJob> {
    @Override
    public @NonNull RemoteConfigRefreshJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      return new RemoteConfigRefreshJob(parameters);
    }
  }
}
