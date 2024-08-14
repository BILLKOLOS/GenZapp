package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.database.RecipientTable;
import org.thoughtcrime.securesms.database.RecipientTable.RecipientReader;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.GenZappservice.api.GenZappServiceMessageSender;
import org.whispersystems.GenZappservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.GenZappservice.api.messages.multidevice.BlockedListMessage;
import org.whispersystems.GenZappservice.api.messages.multidevice.GenZappServiceSyncMessage;
import org.whispersystems.GenZappservice.api.push.GenZappServiceAddress;
import org.whispersystems.GenZappservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.GenZappservice.api.push.exceptions.ServerRejectedException;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MultiDeviceBlockedUpdateJob extends BaseJob {

  public static final String KEY = "MultiDeviceBlockedUpdateJob";

  @SuppressWarnings("unused")
  private static final String TAG = Log.tag(MultiDeviceBlockedUpdateJob.class);

  public MultiDeviceBlockedUpdateJob() {
    this(new Job.Parameters.Builder()
                           .addConstraint(NetworkConstraint.KEY)
                           .setQueue("MultiDeviceBlockedUpdateJob")
                           .setLifespan(TimeUnit.DAYS.toMillis(1))
                           .setMaxAttempts(Parameters.UNLIMITED)
                           .build());
  }

  private MultiDeviceBlockedUpdateJob(@NonNull Job.Parameters parameters) {
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
  public void onRun()
      throws IOException, UntrustedIdentityException
  {
    if (!Recipient.self().isRegistered()) {
      throw new NotPushRegisteredException();
    }

    if (!TextSecurePreferences.isMultiDevice(context)) {
      Log.i(TAG, "Not multi device, aborting...");
      return;
    }

    RecipientTable database = GenZappDatabase.recipients();

    try (RecipientReader reader = database.readerForBlocked(database.getBlocked())) {
      List<GenZappServiceAddress> blockedIndividuals = new LinkedList<>();
      List<byte[]>               blockedGroups      = new LinkedList<>();

      Recipient recipient;

      while ((recipient = reader.getNext()) != null) {
        if (recipient.isPushGroup()) {
          blockedGroups.add(recipient.requireGroupId().getDecodedId());
        } else if (recipient.isMaybeRegistered() && (recipient.getHasServiceId() || recipient.getHasE164())) {
          blockedIndividuals.add(RecipientUtil.toGenZappServiceAddress(context, recipient));
        }
      }

      GenZappServiceMessageSender messageSender = AppDependencies.getGenZappServiceMessageSender();
      messageSender.sendSyncMessage(GenZappServiceSyncMessage.forBlocked(new BlockedListMessage(blockedIndividuals, blockedGroups))
      );
    }
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception exception) {
    if (exception instanceof ServerRejectedException) return false;
    if (exception instanceof PushNetworkException) return true;
    return false;
  }

  @Override
  public void onFailure() {
  }

  public static final class Factory implements Job.Factory<MultiDeviceBlockedUpdateJob> {
    @Override
    public @NonNull MultiDeviceBlockedUpdateJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      return new MultiDeviceBlockedUpdateJob(parameters);
    }
  }
}
