package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.GenZapp.core.util.logging.Log;
import org.GenZapp.libGenZapp.protocol.state.GenZappProtocolStore;
import org.thoughtcrime.securesms.crypto.PreKeyUtil;
import org.thoughtcrime.securesms.crypto.storage.PreKeyMetadataStore;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;

/**
 * Deprecated. Only exists for previously-enqueued jobs. 
 * Use {@link PreKeyUtil#cleanSignedPreKeys(GenZappProtocolStore, PreKeyMetadataStore)} instead.
 */
@Deprecated
public class CleanPreKeysJob extends BaseJob {

  public static final String KEY = "CleanPreKeysJob";

  private static final String TAG = Log.tag(CleanPreKeysJob.class);

  private CleanPreKeysJob(@NonNull Job.Parameters parameters) {
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
  public void onRun() {
    PreKeyUtil.cleanSignedPreKeys(AppDependencies.getProtocolStore().aci(), GenZappStore.account().aciPreKeys());
    PreKeyUtil.cleanSignedPreKeys(AppDependencies.getProtocolStore().pni(), GenZappStore.account().pniPreKeys());
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception throwable) {
    return false;
  }

  @Override
  public void onFailure() {
    Log.w(TAG, "Failed to execute clean signed prekeys task.");
  }

  public static final class Factory implements Job.Factory<CleanPreKeysJob> {
    @Override
    public @NonNull CleanPreKeysJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      return new CleanPreKeysJob(parameters);
    }
  }
}
