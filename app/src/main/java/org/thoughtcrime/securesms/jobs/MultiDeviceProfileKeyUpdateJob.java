package org.thoughtcrime.securesms.jobs;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.GenZapp.core.util.logging.Log;
import org.GenZapp.libGenZapp.zkgroup.profiles.ProfileKey;
import org.thoughtcrime.securesms.crypto.ProfileKeyUtil;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.GenZappservice.api.GenZappServiceMessageSender;
import org.whispersystems.GenZappservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.GenZappservice.api.messages.GenZappServiceAttachment;
import org.whispersystems.GenZappservice.api.messages.GenZappServiceAttachmentStream;
import org.whispersystems.GenZappservice.api.messages.multidevice.ContactsMessage;
import org.whispersystems.GenZappservice.api.messages.multidevice.DeviceContact;
import org.whispersystems.GenZappservice.api.messages.multidevice.DeviceContactsOutputStream;
import org.whispersystems.GenZappservice.api.messages.multidevice.GenZappServiceSyncMessage;
import org.whispersystems.GenZappservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.GenZappservice.api.push.exceptions.ServerRejectedException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class MultiDeviceProfileKeyUpdateJob extends BaseJob {

  public static String KEY = "MultiDeviceProfileKeyUpdateJob";

  private static final String TAG = Log.tag(MultiDeviceProfileKeyUpdateJob.class);

  public MultiDeviceProfileKeyUpdateJob() {
    this(new Job.Parameters.Builder()
                           .addConstraint(NetworkConstraint.KEY)
                           .setQueue("MultiDeviceProfileKeyUpdateJob")
                           .setLifespan(TimeUnit.DAYS.toMillis(1))
                           .setMaxAttempts(Parameters.UNLIMITED)
                           .build());
  }

  private MultiDeviceProfileKeyUpdateJob(@NonNull Job.Parameters parameters) {
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
      Log.i(TAG, "Not multi device...");
      return;
    }

    Optional<ProfileKey>  profileKey = Optional.of(ProfileKeyUtil.getSelfProfileKey());
    ByteArrayOutputStream baos       = new ByteArrayOutputStream();
    DeviceContactsOutputStream out        = new DeviceContactsOutputStream(baos);

    out.write(new DeviceContact(Optional.ofNullable(GenZappStore.account().getAci()),
                                Optional.ofNullable(GenZappStore.account().getE164()),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                profileKey,
                                Optional.empty(),
                                Optional.empty(),
                                false));

    out.close();

    GenZappServiceMessageSender    messageSender    = AppDependencies.getGenZappServiceMessageSender();
    GenZappServiceAttachmentStream attachmentStream = GenZappServiceAttachment.newStreamBuilder()
                                                                            .withStream(new ByteArrayInputStream(baos.toByteArray()))
                                                                            .withContentType("application/octet-stream")
                                                                            .withLength(baos.toByteArray().length)
                                                                            .withResumableUploadSpec(messageSender.getResumableUploadSpec())
                                                                            .build();

    GenZappServiceSyncMessage      syncMessage      = GenZappServiceSyncMessage.forContacts(new ContactsMessage(attachmentStream, false));

    messageSender.sendSyncMessage(syncMessage);
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception exception) {
    if (exception instanceof ServerRejectedException) return false;
    if (exception instanceof PushNetworkException) return true;
    return false;
  }

  @Override
  public void onFailure() {
    Log.w(TAG, "Profile key sync failed!");
  }

  public static final class Factory implements Job.Factory<MultiDeviceProfileKeyUpdateJob> {
    @Override
    public @NonNull MultiDeviceProfileKeyUpdateJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      return new MultiDeviceProfileKeyUpdateJob(parameters);
    }
  }
}
