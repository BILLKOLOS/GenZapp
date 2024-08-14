package org.thoughtcrime.securesms.jobs;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.GenZappservice.api.GenZappServiceMessageSender;
import org.whispersystems.GenZappservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.GenZappservice.api.messages.multidevice.KeysMessage;
import org.whispersystems.GenZappservice.api.messages.multidevice.GenZappServiceSyncMessage;
import org.whispersystems.GenZappservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.GenZappservice.api.push.exceptions.ServerRejectedException;
import org.whispersystems.GenZappservice.api.storage.StorageKey;

import java.io.IOException;
import java.util.Optional;

public class MultiDeviceKeysUpdateJob extends BaseJob {

  public static final String KEY = "MultiDeviceKeysUpdateJob";

  private static final String TAG = Log.tag(MultiDeviceKeysUpdateJob.class);

  public MultiDeviceKeysUpdateJob() {
    this(new Parameters.Builder()
                           .setQueue("MultiDeviceKeysUpdateJob")
                           .setMaxInstancesForFactory(2)
                           .addConstraint(NetworkConstraint.KEY)
                           .setMaxAttempts(10)
                           .build());

  }

  private MultiDeviceKeysUpdateJob(@NonNull Parameters parameters) {
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
  public void onRun() throws IOException, UntrustedIdentityException {
    if (!Recipient.self().isRegistered()) {
      throw new NotPushRegisteredException();
    }

    if (!TextSecurePreferences.isMultiDevice(context)) {
      Log.i(TAG, "Not multi device, aborting...");
      return;
    }

    if (GenZappStore.account().isLinkedDevice()) {
      Log.i(TAG, "Not primary device, aborting...");
      return;
    }

    GenZappServiceMessageSender messageSender     = AppDependencies.getGenZappServiceMessageSender();
    StorageKey                 storageServiceKey = GenZappStore.storageService().getOrCreateStorageKey();

    messageSender.sendSyncMessage(GenZappServiceSyncMessage.forKeys(new KeysMessage(Optional.ofNullable(storageServiceKey), Optional.of(GenZappStore.svr().getOrCreateMasterKey())))
    );
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception e) {
    if (e instanceof ServerRejectedException) return false;
    return e instanceof PushNetworkException;
  }

  @Override
  public void onFailure() {
  }

  public static final class Factory implements Job.Factory<MultiDeviceKeysUpdateJob> {
    @Override
    public @NonNull MultiDeviceKeysUpdateJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      return new MultiDeviceKeysUpdateJob(parameters);
    }
  }
}
