package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.crypto.SealedSenderAccessUtil;
import org.thoughtcrime.securesms.database.PaymentTable;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.JsonJobData;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.thoughtcrime.securesms.transport.RetryLaterException;
import org.whispersystems.GenZappservice.api.GenZappServiceMessageSender;
import org.whispersystems.GenZappservice.api.GenZappServiceMessageSender.IndividualSendEvents;
import org.whispersystems.GenZappservice.api.crypto.ContentHint;
import org.whispersystems.GenZappservice.api.messages.SendMessageResult;
import org.whispersystems.GenZappservice.api.messages.GenZappServiceDataMessage;
import org.whispersystems.GenZappservice.api.push.GenZappServiceAddress;
import org.whispersystems.GenZappservice.api.push.exceptions.ServerRejectedException;

import java.io.IOException;
import java.util.UUID;

public final class PaymentNotificationSendJob extends BaseJob {

  public static final String KEY = "PaymentNotificationSendJob";

  private static final String TAG = Log.tag(PaymentNotificationSendJob.class);

  private static final String KEY_UUID      = "uuid";
  private static final String KEY_RECIPIENT = "recipient";

  private final RecipientId recipientId;
  private final UUID        uuid;

  public static Job create(@NonNull RecipientId recipientId, @NonNull UUID uuid, @NonNull String queue) {
    return new PaymentNotificationSendJobV2(recipientId, uuid);
  }

  private PaymentNotificationSendJob(@NonNull Parameters parameters,
                                     @NonNull RecipientId recipientId,
                                     @NonNull UUID uuid)
  {
    super(parameters);

    this.recipientId = recipientId;
    this.uuid        = uuid;
  }

  @Override
  public @Nullable byte[] serialize() {
    return new JsonJobData.Builder()
                   .putString(KEY_RECIPIENT, recipientId.serialize())
                   .putString(KEY_UUID, uuid.toString())
                   .serialize();
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

    PaymentTable paymentDatabase = GenZappDatabase.payments();
    Recipient    recipient       = Recipient.resolved(recipientId);

    if (recipient.isUnregistered()) {
      Log.w(TAG, recipientId + " not registered!");
      return;
    }

    GenZappServiceMessageSender messageSender = AppDependencies.getGenZappServiceMessageSender();
    GenZappServiceAddress       address       = RecipientUtil.toGenZappServiceAddress(context, recipient);

    PaymentTable.PaymentTransaction payment = paymentDatabase.getPayment(uuid);

    if (payment == null) {
      Log.w(TAG, "Could not find payment, cannot send notification " + uuid);
      return;
    }

    if (payment.getReceipt() == null) {
      Log.w(TAG, "Could not find payment receipt, cannot send notification " + uuid);
      return;
    }

    GenZappServiceDataMessage dataMessage = GenZappServiceDataMessage.newBuilder()
                                                                   .withPayment(new GenZappServiceDataMessage.Payment(new GenZappServiceDataMessage.PaymentNotification(payment.getReceipt(), payment.getNote()), null))
                                                                   .build();

    SendMessageResult sendMessageResult = messageSender.sendDataMessage(address,
                                                                        SealedSenderAccessUtil.getSealedSenderAccessFor(recipient),
                                                                        ContentHint.DEFAULT,
                                                                        dataMessage,
                                                                        IndividualSendEvents.EMPTY,
                                                                        false,
                                                                        recipient.getNeedsPniSignature());

    if (recipient.getNeedsPniSignature()) {
      GenZappDatabase.pendingPniSignatureMessages().insertIfNecessary(recipientId, dataMessage.getTimestamp(), sendMessageResult);
    }

    if (sendMessageResult.getIdentityFailure() != null) {
      Log.w(TAG, "Identity failure for " + recipient.getId());
    } else if (sendMessageResult.isUnregisteredFailure()) {
      Log.w(TAG, "Unregistered failure for " + recipient.getId());
    } else if (sendMessageResult.getSuccess() == null) {
      throw new RetryLaterException();
    } else {
      Log.i(TAG, String.format("Payment notification sent to %s for %s", recipientId, uuid));
    }
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception e) {
    if (e instanceof ServerRejectedException) return false;
    if (e instanceof NotPushRegisteredException) return false;
    return e instanceof IOException ||
           e instanceof RetryLaterException;
  }

  @Override
  public void onFailure() {
    Log.w(TAG, String.format("Failed to send payment notification to recipient %s for %s", recipientId, uuid));
  }

  public static class Factory implements Job.Factory<PaymentNotificationSendJob> {
    @Override
    public @NonNull PaymentNotificationSendJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      JsonJobData data = JsonJobData.deserialize(serializedData);

      return new PaymentNotificationSendJob(parameters,
                                            RecipientId.from(data.getString(KEY_RECIPIENT)),
                                            UUID.fromString(data.getString(KEY_UUID)));
    }
  }
}
