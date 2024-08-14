package org.thoughtcrime.securesms.jobs;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.annimon.stream.Stream;

import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.crypto.SealedSenderAccessUtil;
import org.thoughtcrime.securesms.database.MessageTable;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.database.PaymentTable;
import org.thoughtcrime.securesms.database.RecipientTable.SealedSenderAccessMode;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.database.model.MessageId;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobmanager.JsonJobData;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.OutgoingMessage;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.thoughtcrime.securesms.service.ExpiringMessageManager;
import org.thoughtcrime.securesms.transport.RetryLaterException;
import org.thoughtcrime.securesms.transport.UndeliverableMessageException;
import org.thoughtcrime.securesms.util.GenZappLocalMetrics;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.GenZappservice.api.GenZappServiceMessageSender;
import org.whispersystems.GenZappservice.api.GenZappServiceMessageSender.IndividualSendEvents;
import org.whispersystems.GenZappservice.api.crypto.ContentHint;
import org.whispersystems.GenZappservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.GenZappservice.api.messages.SendMessageResult;
import org.whispersystems.GenZappservice.api.messages.GenZappServiceAttachment;
import org.whispersystems.GenZappservice.api.messages.GenZappServiceDataMessage;
import org.whispersystems.GenZappservice.api.messages.GenZappServiceEditMessage;
import org.whispersystems.GenZappservice.api.messages.GenZappServicePreview;
import org.whispersystems.GenZappservice.api.messages.shared.SharedContact;
import org.whispersystems.GenZappservice.api.push.GenZappServiceAddress;
import org.whispersystems.GenZappservice.api.push.exceptions.ProofRequiredException;
import org.whispersystems.GenZappservice.api.push.exceptions.ServerRejectedException;
import org.whispersystems.GenZappservice.api.push.exceptions.UnregisteredUserException;
import org.whispersystems.GenZappservice.api.util.UuidUtil;
import org.whispersystems.GenZappservice.internal.push.BodyRange;
import org.whispersystems.GenZappservice.internal.push.DataMessage;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class IndividualSendJob extends PushSendJob {

  public static final String KEY = "PushMediaSendJob";

  private static final String TAG = Log.tag(IndividualSendJob.class);

  private static final String KEY_MESSAGE_ID = "message_id";

  private final long messageId;

  public IndividualSendJob(long messageId, @NonNull Recipient recipient, boolean hasMedia, boolean isScheduledSend) {
    this(new Parameters.Builder()
                       .setQueue(isScheduledSend ? recipient.getId().toScheduledSendQueueKey() : recipient.getId().toQueueKey(hasMedia))
                       .addConstraint(NetworkConstraint.KEY)
                       .setLifespan(TimeUnit.DAYS.toMillis(1))
                       .setMaxAttempts(Parameters.UNLIMITED)
                       .build(),
         messageId);
  }

  private IndividualSendJob(Job.Parameters parameters, long messageId) {
    super(parameters);
    this.messageId = messageId;
  }

  public static Job create(long messageId, @NonNull Recipient recipient, boolean hasMedia, boolean isScheduledSend) {
    if (!recipient.getHasServiceId()) {
      throw new AssertionError("No ServiceId!");
    }

    if (recipient.isGroup()) {
      throw new AssertionError("This job does not send group messages!");
    }

    return new IndividualSendJob(messageId, recipient, hasMedia, isScheduledSend);
  }

  @WorkerThread
  public static void enqueue(@NonNull Context context, @NonNull JobManager jobManager, long messageId, @NonNull Recipient recipient, boolean isScheduledSend) {
    try {
      OutgoingMessage message = GenZappDatabase.messages().getOutgoingMessage(messageId);
      if (message.getScheduledDate() != -1) {
        AppDependencies.getScheduledMessageManager().scheduleIfNecessary();
        return;
      }

      Set<String> attachmentUploadIds = enqueueCompressingAndUploadAttachmentsChains(jobManager, message);
      boolean     hasMedia            = attachmentUploadIds.size() > 0;
      boolean     addHardDependencies = hasMedia && !isScheduledSend;

      jobManager.add(IndividualSendJob.create(messageId, recipient, hasMedia, isScheduledSend),
                     attachmentUploadIds,
                     addHardDependencies ? recipient.getId().toQueueKey() : null);
    } catch (NoSuchMessageException | MmsException e) {
      Log.w(TAG, "Failed to enqueue message.", e);
      GenZappDatabase.messages().markAsSentFailed(messageId);
      notifyMediaMessageDeliveryFailed(context, messageId);
    }
  }

  @Override
  public @Nullable byte[] serialize() {
    return new JsonJobData.Builder().putLong(KEY_MESSAGE_ID, messageId).serialize();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onAdded() {
    GenZappDatabase.messages().markAsSending(messageId);
  }

  @Override
  public void onPushSend()
      throws IOException, MmsException, NoSuchMessageException, UndeliverableMessageException, RetryLaterException
  {
    GenZappLocalMetrics.IndividualMessageSend.onJobStarted(messageId);

    ExpiringMessageManager expirationManager = AppDependencies.getExpiringMessageManager();
    MessageTable    database              = GenZappDatabase.messages();
    OutgoingMessage message               = database.getOutgoingMessage(messageId);
    long            threadId              = database.getMessageRecord(messageId).getThreadId();
    MessageRecord   originalEditedMessage = message.getMessageToEdit() > 0 ? GenZappDatabase.messages().getMessageRecordOrNull(message.getMessageToEdit()) : null;

    if (database.isSent(messageId)) {
      warn(TAG, String.valueOf(message.getSentTimeMillis()), "Message " + messageId + " was already sent. Ignoring.");
      return;
    }

    try {
      log(TAG, String.valueOf(message.getSentTimeMillis()), "Sending message: " + messageId + ", Recipient: " + message.getThreadRecipient().getId() + ", Thread: " + threadId + ", Attachments: " + buildAttachmentString(message.getAttachments()) + ", Editing: " + (originalEditedMessage != null ? originalEditedMessage.getDateSent() : "N/A"));

      RecipientUtil.shareProfileIfFirstSecureMessage(message.getThreadRecipient());

      Recipient              recipient  = message.getThreadRecipient().fresh();
      byte[]                 profileKey = recipient.getProfileKey();
      SealedSenderAccessMode accessMode = recipient.getSealedSenderAccessMode();

      boolean unidentified = deliver(message, originalEditedMessage);

      database.markAsSent(messageId, true);
      markAttachmentsUploaded(messageId, message);
      database.markUnidentified(messageId, unidentified);

      // For scheduled messages, which may not have updated the thread with it's snippet yet
      GenZappDatabase.threads().updateSilently(threadId, false);

      if (recipient.isSelf()) {
        GenZappDatabase.messages().incrementDeliveryReceiptCount(message.getSentTimeMillis(), recipient.getId(), System.currentTimeMillis());
        GenZappDatabase.messages().incrementReadReceiptCount(message.getSentTimeMillis(), recipient.getId(), System.currentTimeMillis());
        GenZappDatabase.messages().incrementViewedReceiptCount(message.getSentTimeMillis(), recipient.getId(), System.currentTimeMillis());
      }

      if (unidentified && accessMode == SealedSenderAccessMode.UNKNOWN && profileKey == null) {
        log(TAG, String.valueOf(message.getSentTimeMillis()), "Marking recipient as UD-unrestricted following a UD send.");
        GenZappDatabase.recipients().setSealedSenderAccessMode(recipient.getId(), SealedSenderAccessMode.UNRESTRICTED);
      } else if (unidentified && accessMode == SealedSenderAccessMode.UNKNOWN) {
        log(TAG, String.valueOf(message.getSentTimeMillis()), "Marking recipient as UD-enabled following a UD send.");
        GenZappDatabase.recipients().setSealedSenderAccessMode(recipient.getId(), SealedSenderAccessMode.ENABLED);
      } else if (!unidentified && accessMode != SealedSenderAccessMode.DISABLED) {
        log(TAG, String.valueOf(message.getSentTimeMillis()), "Marking recipient as UD-disabled following a non-UD send.");
        GenZappDatabase.recipients().setSealedSenderAccessMode(recipient.getId(), SealedSenderAccessMode.DISABLED);
      }

      if (originalEditedMessage != null && originalEditedMessage.getExpireStarted() > 0) {
        database.markExpireStarted(messageId, originalEditedMessage.getExpireStarted());
        expirationManager.scheduleDeletion(messageId, true, originalEditedMessage.getExpireStarted(), originalEditedMessage.getExpiresIn());
      } else if (message.getExpiresIn() > 0 && !message.isExpirationUpdate()) {
        database.markExpireStarted(messageId);
        expirationManager.scheduleDeletion(messageId, true, message.getExpiresIn());
      }

      if (message.isViewOnce()) {
        GenZappDatabase.attachments().deleteAttachmentFilesForViewOnceMessage(messageId);
      }

      ConversationShortcutRankingUpdateJob.enqueueForOutgoingIfNecessary(recipient);

      log(TAG, String.valueOf(message.getSentTimeMillis()), "Sent message: " + messageId);

    } catch (UnregisteredUserException uue) {
      warn(TAG, "Failure", uue);
      database.markAsSentFailed(messageId);
      notifyMediaMessageDeliveryFailed(context, messageId);
      AppDependencies.getJobManager().add(new DirectoryRefreshJob(false));
    } catch (UntrustedIdentityException uie) {
      warn(TAG, "Failure", uie);
      RecipientId recipientId = Recipient.external(context, uie.getIdentifier()).getId();
      database.addMismatchedIdentity(messageId, recipientId, uie.getIdentityKey());
      database.markAsSentFailed(messageId);
      RetrieveProfileJob.enqueue(recipientId);
    } catch (ProofRequiredException e) {
      handleProofRequiredException(context, e, GenZappDatabase.threads().getRecipientForThreadId(threadId), threadId, messageId, true);
    }

    GenZappLocalMetrics.IndividualMessageSend.onJobFinished(messageId);
  }

  @Override
  public void onRetry() {
    GenZappLocalMetrics.IndividualMessageSend.cancel(messageId);
    super.onRetry();
  }

  @Override
  public void onFailure() {
    GenZappLocalMetrics.IndividualMessageSend.cancel(messageId);
    GenZappDatabase.messages().markAsSentFailed(messageId);
    notifyMediaMessageDeliveryFailed(context, messageId);
  }

  private boolean deliver(OutgoingMessage message, MessageRecord originalEditedMessage)
      throws IOException, UnregisteredUserException, UntrustedIdentityException, UndeliverableMessageException
  {
    if (message.getThreadRecipient() == null) {
      throw new UndeliverableMessageException("No destination address.");
    }

    try {
      rotateSenderCertificateIfNecessary();

      Recipient messageRecipient = message.getThreadRecipient().fresh();

      if (messageRecipient.isUnregistered()) {
        throw new UndeliverableMessageException(messageRecipient.getId() + " not registered!");
      }

      GenZappServiceMessageSender                 messageSender       = AppDependencies.getGenZappServiceMessageSender();
      GenZappServiceAddress                       address             = RecipientUtil.toGenZappServiceAddress(context, messageRecipient);
      List<Attachment>                           attachments         = Stream.of(message.getAttachments()).filterNot(Attachment::isSticker).toList();
      List<GenZappServiceAttachment>              serviceAttachments  = getAttachmentPointersFor(attachments);
      Optional<byte[]>                           profileKey          = getProfileKey(messageRecipient);
      Optional<GenZappServiceDataMessage.Sticker> sticker             = getStickerFor(message);
      List<SharedContact>                        sharedContacts      = getSharedContactsFor(message);
      List<GenZappServicePreview>                 previews            = getPreviewsFor(message);
      GenZappServiceDataMessage.GiftBadge         giftBadge           = getGiftBadgeFor(message);
      GenZappServiceDataMessage.Payment           payment             = getPayment(message);
      List<BodyRange>                            bodyRanges          = getBodyRanges(message);
      GenZappServiceDataMessage.Builder           mediaMessageBuilder = GenZappServiceDataMessage.newBuilder()
                                                                                               .withBody(message.getBody())
                                                                                               .withAttachments(serviceAttachments)
                                                                                               .withTimestamp(message.getSentTimeMillis())
                                                                                               .withExpiration((int)(message.getExpiresIn() / 1000))
                                                                                               .withViewOnce(message.isViewOnce())
                                                                                               .withProfileKey(profileKey.orElse(null))
                                                                                               .withSticker(sticker.orElse(null))
                                                                                               .withSharedContacts(sharedContacts)
                                                                                               .withPreviews(previews)
                                                                                               .withGiftBadge(giftBadge)
                                                                                               .asExpirationUpdate(message.isExpirationUpdate())
                                                                                               .asEndSessionMessage(message.isEndSession())
                                                                                               .withPayment(payment)
                                                                                               .withBodyRanges(bodyRanges);

      if (message.getParentStoryId() != null) {
        try {
          MessageRecord storyRecord    = GenZappDatabase.messages().getMessageRecord(message.getParentStoryId().asMessageId().getId());
          Recipient     storyRecipient = storyRecord.getFromRecipient();

          GenZappServiceDataMessage.StoryContext storyContext = new GenZappServiceDataMessage.StoryContext(storyRecipient.requireServiceId(), storyRecord.getDateSent());
          mediaMessageBuilder.withStoryContext(storyContext);

          Optional<GenZappServiceDataMessage.Reaction> reaction = getStoryReactionFor(message, storyContext);
          if (reaction.isPresent()) {
            mediaMessageBuilder.withReaction(reaction.get());
            mediaMessageBuilder.withBody(null);
          }
        } catch (NoSuchMessageException e) {
          throw new UndeliverableMessageException(e);
        }
      } else {
        mediaMessageBuilder.withQuote(getQuoteFor(message).orElse(null));
      }

      if (message.getGiftBadge() != null || message.isPaymentsNotification()) {
        mediaMessageBuilder.withBody(null);
      }

      GenZappServiceDataMessage mediaMessage = mediaMessageBuilder.build();

      if (originalEditedMessage != null) {
        if (Util.equals(GenZappStore.account().getAci(), address.getServiceId())) {
          SendMessageResult                result     = messageSender.sendSelfSyncEditMessage(new GenZappServiceEditMessage(originalEditedMessage.getDateSent(), mediaMessage));
          GenZappDatabase.messageLog().insertIfPossible(messageRecipient.getId(), message.getSentTimeMillis(), result, ContentHint.RESENDABLE, new MessageId(messageId), false);

          return SealedSenderAccessUtil.getSealedSenderCertificate() != null;
        } else {
          SendMessageResult result = messageSender.sendEditMessage(address,
                                                                   SealedSenderAccessUtil.getSealedSenderAccessFor(messageRecipient),
                                                                   ContentHint.RESENDABLE,
                                                                   mediaMessage,
                                                                   IndividualSendEvents.EMPTY,
                                                                   message.isUrgent(),
                                                                   originalEditedMessage.getDateSent());
          GenZappDatabase.messageLog().insertIfPossible(messageRecipient.getId(), message.getSentTimeMillis(), result, ContentHint.RESENDABLE, new MessageId(messageId), false);

          return result.getSuccess().isUnidentified();
        }
      } else if (Util.equals(GenZappStore.account().getAci(), address.getServiceId())) {
        SendMessageResult                result     = messageSender.sendSyncMessage(mediaMessage);
        GenZappDatabase.messageLog().insertIfPossible(messageRecipient.getId(), message.getSentTimeMillis(), result, ContentHint.RESENDABLE, new MessageId(messageId), false);
        return SealedSenderAccessUtil.getSealedSenderCertificate() != null;
      } else {
        GenZappLocalMetrics.IndividualMessageSend.onDeliveryStarted(messageId, message.getSentTimeMillis());
        SendMessageResult result = messageSender.sendDataMessage(address,
                                                                 SealedSenderAccessUtil.getSealedSenderAccessFor(messageRecipient),
                                                                 ContentHint.RESENDABLE,
                                                                 mediaMessage,
                                                                 new MetricEventListener(messageId),
                                                                 message.isUrgent(),
                                                                 messageRecipient.getNeedsPniSignature());

        GenZappDatabase.messageLog().insertIfPossible(messageRecipient.getId(), message.getSentTimeMillis(), result, ContentHint.RESENDABLE, new MessageId(messageId), message.isUrgent());

        if (messageRecipient.getNeedsPniSignature()) {
          GenZappDatabase.pendingPniSignatureMessages().insertIfNecessary(messageRecipient.getId(), message.getSentTimeMillis(), result);
        }

        return result.getSuccess().isUnidentified();
      }
    } catch (FileNotFoundException e) {
      warn(TAG, String.valueOf(message.getSentTimeMillis()), e);
      throw new UndeliverableMessageException(e);
    } catch (ServerRejectedException e) {
      throw new UndeliverableMessageException(e);
    }
  }

  private GenZappServiceDataMessage.Payment getPayment(OutgoingMessage message) {
    if (message.isPaymentsNotification()) {
      UUID                            paymentUuid = UuidUtil.parseOrThrow(message.getBody());
      PaymentTable.PaymentTransaction payment     = GenZappDatabase.payments().getPayment(paymentUuid);

      if (payment == null) {
        Log.w(TAG, "Could not find payment, cannot send notification " + paymentUuid);
        return null;
      }

      if (payment.getReceipt() == null) {
        Log.w(TAG, "Could not find payment receipt, cannot send notification " + paymentUuid);
        return null;
      }

      return new GenZappServiceDataMessage.Payment(new GenZappServiceDataMessage.PaymentNotification(payment.getReceipt(), payment.getNote()), null);
    } else {
      DataMessage.Payment.Activation.Type type = null;

      if (message.isRequestToActivatePayments()) {
        type = DataMessage.Payment.Activation.Type.REQUEST;
      } else if (message.isPaymentsActivated()) {
        type = DataMessage.Payment.Activation.Type.ACTIVATED;
      }

      if (type != null) {
        return new GenZappServiceDataMessage.Payment(null, new GenZappServiceDataMessage.PaymentActivation(type));
      } else {
        return null;
      }
    }
  }

  public static long getMessageId(@Nullable byte[] serializedData) {
    JsonJobData data = JsonJobData.deserialize(serializedData);
    return data.getLong(KEY_MESSAGE_ID);
  }
  private static class MetricEventListener implements GenZappServiceMessageSender.IndividualSendEvents {
    private final long messageId;

    private MetricEventListener(long messageId) {
      this.messageId = messageId;
    }

    @Override
    public void onMessageEncrypted() {
      GenZappLocalMetrics.IndividualMessageSend.onMessageEncrypted(messageId);
    }

    @Override
    public void onMessageSent() {
      GenZappLocalMetrics.IndividualMessageSend.onMessageSent(messageId);
    }

    @Override
    public void onSyncMessageSent() {
      GenZappLocalMetrics.IndividualMessageSend.onSyncMessageSent(messageId);
    }
  }

  public static final class Factory implements Job.Factory<IndividualSendJob> {
    @Override
    public @NonNull IndividualSendJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      JsonJobData data = JsonJobData.deserialize(serializedData);
      return new IndividualSendJob(parameters, data.getLong(KEY_MESSAGE_ID));
    }
  }
}
