package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.GenZapp.core.util.Hex;
import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.database.StickerTable.StickerPackRecordReader;
import org.thoughtcrime.securesms.database.model.StickerPackRecord;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.GenZappservice.api.GenZappServiceMessageSender;
import org.whispersystems.GenZappservice.api.messages.multidevice.GenZappServiceSyncMessage;
import org.whispersystems.GenZappservice.api.messages.multidevice.StickerPackOperationMessage;
import org.whispersystems.GenZappservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.GenZappservice.api.push.exceptions.ServerRejectedException;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Tells a linked desktop about all installed sticker packs.
 */
public class MultiDeviceStickerPackSyncJob extends BaseJob {

  private static final String TAG = Log.tag(MultiDeviceStickerPackSyncJob.class);

  public static final String KEY = "MultiDeviceStickerPackSyncJob";

  public MultiDeviceStickerPackSyncJob() {
    this(new Parameters.Builder()
                           .setQueue("MultiDeviceStickerPackSyncJob")
                           .addConstraint(NetworkConstraint.KEY)
                           .setLifespan(TimeUnit.DAYS.toMillis(1))
                           .build());
  }

  public MultiDeviceStickerPackSyncJob(@NonNull Parameters parameters) {
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
    if (!Recipient.self().isRegistered()) {
      throw new NotPushRegisteredException();
    }

    if (!TextSecurePreferences.isMultiDevice(context)) {
      Log.i(TAG, "Not multi device, aborting...");
      return;
    }

    List<StickerPackOperationMessage> operations = new LinkedList<>();

    try (StickerPackRecordReader reader = new StickerPackRecordReader(GenZappDatabase.stickers().getInstalledStickerPacks())) {
      StickerPackRecord pack;
      while ((pack = reader.getNext()) != null) {
        byte[] packIdBytes  = Hex.fromStringCondensed(pack.getPackId());
        byte[] packKeyBytes = Hex.fromStringCondensed(pack.getPackKey());

        operations.add(new StickerPackOperationMessage(packIdBytes, packKeyBytes, StickerPackOperationMessage.Type.INSTALL));
      }
    }

    GenZappServiceMessageSender messageSender = AppDependencies.getGenZappServiceMessageSender();
    messageSender.sendSyncMessage(GenZappServiceSyncMessage.forStickerPackOperations(operations)
    );
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception e) {
    if (e instanceof ServerRejectedException) return false;
    return e instanceof PushNetworkException;
  }

  @Override
  public void onFailure() {
    Log.w(TAG, "Failed to sync sticker pack operation!");
  }

  public static class Factory implements Job.Factory<MultiDeviceStickerPackSyncJob> {

    @Override
    public @NonNull
    MultiDeviceStickerPackSyncJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      return new MultiDeviceStickerPackSyncJob(parameters);
    }
  }
}
