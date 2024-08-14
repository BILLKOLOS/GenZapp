package org.thoughtcrime.securesms.messages;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.GenZapp.core.util.logging.Log;
import org.GenZapp.libGenZapp.metadata.certificate.SenderCertificate;
import org.GenZapp.libGenZapp.protocol.InvalidKeyException;
import org.GenZapp.libGenZapp.protocol.InvalidRegistrationIdException;
import org.GenZapp.libGenZapp.protocol.NoSessionException;
import org.GenZapp.libGenZapp.zkgroup.groups.GroupSecretParams;
import org.GenZapp.libGenZapp.zkgroup.groupsend.GroupSendEndorsement;
import org.GenZapp.libGenZapp.zkgroup.groupsend.GroupSendFullToken;
import org.thoughtcrime.securesms.crypto.SenderKeyUtil;
import org.thoughtcrime.securesms.crypto.SealedSenderAccessUtil;
import org.thoughtcrime.securesms.database.MessageSendLogTables;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.database.model.DistributionListId;
import org.thoughtcrime.securesms.database.model.GroupRecord;
import org.thoughtcrime.securesms.database.model.GroupSendEndorsementRecords;
import org.thoughtcrime.securesms.database.model.MessageId;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.groups.GroupChangeException;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.GroupManager;
import org.thoughtcrime.securesms.jobs.RequestGroupV2InfoJob;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.thoughtcrime.securesms.util.RecipientAccessList;
import org.thoughtcrime.securesms.util.RemoteConfig;
import org.thoughtcrime.securesms.util.GenZappLocalMetrics;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.GenZappservice.api.CancelationException;
import org.whispersystems.GenZappservice.api.GenZappServiceMessageSender;
import org.whispersystems.GenZappservice.api.GenZappServiceMessageSender.LegacyGroupEvents;
import org.whispersystems.GenZappservice.api.GenZappServiceMessageSender.SenderKeyGroupEvents;
import org.whispersystems.GenZappservice.api.crypto.ContentHint;
import org.whispersystems.GenZappservice.api.crypto.SealedSenderAccess;
import org.whispersystems.GenZappservice.api.crypto.UnidentifiedAccess;
import org.whispersystems.GenZappservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.GenZappservice.api.groupsv2.GroupSendEndorsements;
import org.whispersystems.GenZappservice.api.messages.SendMessageResult;
import org.whispersystems.GenZappservice.api.messages.GenZappServiceDataMessage;
import org.whispersystems.GenZappservice.api.messages.GenZappServiceEditMessage;
import org.whispersystems.GenZappservice.api.messages.GenZappServiceStoryMessage;
import org.whispersystems.GenZappservice.api.messages.GenZappServiceStoryMessageRecipient;
import org.whispersystems.GenZappservice.api.messages.GenZappServiceTypingMessage;
import org.whispersystems.GenZappservice.api.messages.calls.GenZappServiceCallMessage;
import org.whispersystems.GenZappservice.api.push.DistributionId;
import org.whispersystems.GenZappservice.api.push.ServiceId;
import org.whispersystems.GenZappservice.api.push.GenZappServiceAddress;
import org.whispersystems.GenZappservice.api.push.exceptions.NotFoundException;
import org.whispersystems.GenZappservice.api.util.Preconditions;
import org.whispersystems.GenZappservice.internal.push.exceptions.InvalidUnidentifiedAccessHeaderException;
import org.whispersystems.GenZappservice.internal.push.http.CancelationGenZapp;
import org.whispersystems.GenZappservice.internal.push.http.PartialSendBatchCompleteListener;
import org.whispersystems.GenZappservice.internal.push.http.PartialSendCompleteListener;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public final class GroupSendUtil {

  private static final String TAG = Log.tag(GroupSendUtil.class);

  private GroupSendUtil() {}


  /**
   * Handles all of the logic of sending to a group. Will do sender key sends and legacy 1:1 sends as-needed, and give you back a list of
   * {@link SendMessageResult}s just like we're used to.
   *
   * Messages sent this way, if failed to be decrypted by the receiving party, can be requested to be resent.
   * Note that the ContentHint <em>may not</em> be {@link ContentHint#RESENDABLE} -- it just means that we have an actual record of the message
   * and we <em>could</em> resend it if asked.
   *
   * @param groupId The groupId of the group you're sending to, or null if you're sending to a collection of recipients not joined by a group.
   * @param isRecipientUpdate True if you've already sent this message to some recipients in the past, otherwise false.
   * @param isForStory True if the message is related to a story, and should be sent with the story flag on the envelope
   */
  @WorkerThread
  public static List<SendMessageResult> sendResendableDataMessage(@NonNull Context context,
                                                                  @Nullable GroupId.V2 groupId,
                                                                  @Nullable DistributionListId distributionListId,
                                                                  @NonNull List<Recipient> allTargets,
                                                                  boolean isRecipientUpdate,
                                                                  ContentHint contentHint,
                                                                  @NonNull MessageId messageId,
                                                                  @NonNull GenZappServiceDataMessage message,
                                                                  boolean urgent,
                                                                  boolean isForStory,
                                                                  @Nullable GenZappServiceEditMessage editMessage)
      throws IOException, UntrustedIdentityException
  {
    Preconditions.checkArgument(groupId == null || distributionListId == null, "Cannot supply both a groupId and a distributionListId!");

    DistributionId distributionId = groupId != null ? getDistributionId(groupId) : getDistributionId(distributionListId);

    return sendMessage(context, groupId, distributionId, messageId, allTargets, isRecipientUpdate, isForStory, DataSendOperation.resendable(message, contentHint, messageId, urgent, isForStory, editMessage), null);
  }

  /**
   * Handles all of the logic of sending to a group. Will do sender key sends and legacy 1:1 sends as-needed, and give you back a list of
   * {@link SendMessageResult}s just like we're used to.
   *
   * Messages sent this way, if failed to be decrypted by the receiving party, can *not* be requested to be resent.
   *
   * @param groupId The groupId of the group you're sending to, or null if you're sending to a collection of recipients not joined by a group.
   * @param isRecipientUpdate True if you've already sent this message to some recipients in the past, otherwise false.
   */
  @WorkerThread
  public static List<SendMessageResult> sendUnresendableDataMessage(@NonNull Context context,
                                                                    @Nullable GroupId.V2 groupId,
                                                                    @NonNull List<Recipient> allTargets,
                                                                    boolean isRecipientUpdate,
                                                                    ContentHint contentHint,
                                                                    @NonNull GenZappServiceDataMessage message,
                                                                    boolean urgent)
      throws IOException, UntrustedIdentityException
  {
    return sendMessage(context, groupId, getDistributionId(groupId), null, allTargets, isRecipientUpdate, false, DataSendOperation.unresendable(message, contentHint, urgent), null);
  }

  /**
   * Handles all of the logic of sending to a group. Will do sender key sends and legacy 1:1 sends as-needed, and give you back a list of
   * {@link SendMessageResult}s just like we're used to.
   *
   * @param groupId The groupId of the group you're sending to, or null if you're sending to a collection of recipients not joined by a group.
   */
  @WorkerThread
  public static List<SendMessageResult> sendTypingMessage(@NonNull Context context,
                                                          @Nullable GroupId.V2 groupId,
                                                          @NonNull List<Recipient> allTargets,
                                                          @NonNull GenZappServiceTypingMessage message,
                                                          @Nullable CancelationGenZapp cancelationGenZapp)
      throws IOException, UntrustedIdentityException
  {
    return sendMessage(context, groupId, getDistributionId(groupId), null, allTargets, false, false, new TypingSendOperation(message), cancelationGenZapp);
  }

  /**
   * Handles all of the logic of sending to a group. Will do sender key sends and legacy 1:1 sends as-needed, and give you back a list of
   * {@link SendMessageResult}s just like we're used to.
   *
   * @param groupId The groupId of the group you're sending to, or null if you're sending to a collection of recipients not joined by a group.
   */
  @WorkerThread
  public static List<SendMessageResult> sendCallMessage(@NonNull Context context,
                                                        @Nullable GroupId.V2 groupId,
                                                        @NonNull List<Recipient> allTargets,
                                                        @NonNull GenZappServiceCallMessage message)
      throws IOException, UntrustedIdentityException
  {
    return sendMessage(context, groupId, getDistributionId(groupId), null, allTargets, false, false, new CallSendOperation(message), null);
  }

  /**
   * Handles all of the logic of sending a story to a distribution list. Will do sender key sends and legacy 1:1 sends as-needed, and give you back a list of
   * {@link SendMessageResult}s just like we're used to.
   *
   * @param isRecipientUpdate True if you've already sent this message to some recipients in the past, otherwise false.
   */
  public static List<SendMessageResult> sendStoryMessage(@NonNull Context context,
                                                         @NonNull DistributionListId distributionListId,
                                                         @NonNull List<Recipient> allTargets,
                                                         boolean isRecipientUpdate,
                                                         @NonNull MessageId messageId,
                                                         long sentTimestamp,
                                                         @NonNull GenZappServiceStoryMessage message,
                                                         @NonNull Set<GenZappServiceStoryMessageRecipient> manifest)
      throws IOException, UntrustedIdentityException
  {
    return sendMessage(
        context,
        null,
        getDistributionId(distributionListId),
        messageId,
        allTargets,
        isRecipientUpdate,
        true,
        new StorySendOperation(messageId, null, sentTimestamp, message, manifest),
        null);
  }

  /**
   * Handles all of the logic of sending a story to a group. Will do sender key sends and legacy 1:1 sends as-needed, and give you back a list of
   * {@link SendMessageResult}s just like we're used to.
   *
   * @param isRecipientUpdate True if you've already sent this message to some recipients in the past, otherwise false.
   */
  public static List<SendMessageResult> sendGroupStoryMessage(@NonNull Context context,
                                                              @NonNull GroupId.V2 groupId,
                                                              @NonNull List<Recipient> allTargets,
                                                              boolean isRecipientUpdate,
                                                              @NonNull MessageId messageId,
                                                              long sentTimestamp,
                                                              @NonNull GenZappServiceStoryMessage message)
      throws IOException, UntrustedIdentityException
  {
    return sendMessage(
        context,
        groupId,
        getDistributionId(groupId),
        messageId,
        allTargets,
        isRecipientUpdate,
        true,
        new StorySendOperation(messageId,
                               groupId,
                               sentTimestamp,
                               message,
                               allTargets.stream()
                                         .map(target -> new GenZappServiceStoryMessageRecipient(new GenZappServiceAddress(target.requireServiceId()),
                                                                                               Collections.emptyList(),
                                                                                               true))
                                         .collect(Collectors.toSet())),
        null);
  }

  /**
   * Handles all of the logic of sending to a group. Will do sender key sends and legacy 1:1 sends as-needed, and give you back a list of
   * {@link SendMessageResult}s just like we're used to.
   *
   * @param groupId The groupId of the group you're sending to, or null if you're sending to a collection of recipients not joined by a group.
   * @param isRecipientUpdate True if you've already sent this message to some recipients in the past, otherwise false.
   */
  @WorkerThread
  private static List<SendMessageResult> sendMessage(@NonNull Context context,
                                                     @Nullable GroupId.V2 groupId,
                                                     @Nullable DistributionId distributionId,
                                                     @Nullable MessageId relatedMessageId,
                                                     @NonNull List<Recipient> allTargets,
                                                     boolean isRecipientUpdate,
                                                     boolean isStorySend,
                                                     @NonNull SendOperation sendOperation,
                                                     @Nullable CancelationGenZapp cancelationGenZapp)
      throws IOException, UntrustedIdentityException
  {
    Log.i(TAG, "Starting group send. GroupId: " + (groupId != null ? groupId.toString() : "none") + ", DistributionId: " + (distributionId != null ? distributionId.toString() : "none") + " RelatedMessageId: " + (relatedMessageId != null ? relatedMessageId.toString() : "none") + ", Targets: " + allTargets.size() + ", RecipientUpdate: " + isRecipientUpdate + ", Operation: " + sendOperation.getClass().getSimpleName());

    Set<Recipient>  unregisteredTargets = allTargets.stream().filter(Recipient::isUnregistered).collect(Collectors.toSet());
    List<Recipient> registeredTargets   = allTargets.stream().filter(r -> !unregisteredTargets.contains(r)).collect(Collectors.toList());

    RecipientData               recipients                     = new RecipientData(context, registeredTargets, isStorySend);
    Optional<GroupRecord>       groupRecord                    = groupId != null ? GenZappDatabase.groups().getGroup(groupId) : Optional.empty();
    GroupSendEndorsementRecords groupSendEndorsementRecords    = groupRecord.filter(GroupRecord::isV2Group).map(g -> GenZappDatabase.groups().getGroupSendEndorsements(g.getId())).orElse(null);
    long                        groupSendEndorsementExpiration = groupRecord.map(GroupRecord::getGroupSendEndorsementExpiration).orElse(0L);
    SenderCertificate           senderCertificate              = SealedSenderAccessUtil.getSealedSenderCertificate();
    boolean                     useGroupSendEndorsements       = groupSendEndorsementRecords != null;

    if (useGroupSendEndorsements && senderCertificate == null) {
      Log.w(TAG, "Can't use group send endorsements without a sealed sender certificate, falling back to access key");
      useGroupSendEndorsements = false;
    } else if (useGroupSendEndorsements) {
      boolean refreshGroupSendEndorsements = false;

      if (groupSendEndorsementExpiration == 0) {
        Log.i(TAG, "No group send endorsements expiration set, need to refresh");
        refreshGroupSendEndorsements = true;
      } else if (groupSendEndorsementExpiration - TimeUnit.HOURS.toMillis(2) < System.currentTimeMillis()) {
        Log.i(TAG, "Group send endorsements are expired or expire imminently, refresh. Expires in " + (groupSendEndorsementExpiration - System.currentTimeMillis()) + "ms");
        refreshGroupSendEndorsements = true;
      } else if (groupSendEndorsementRecords.isMissingAnyEndorsements()) {
        Log.i(TAG, "Missing group send endorsements for some members, refresh.");
        refreshGroupSendEndorsements = true;
      }

      if (refreshGroupSendEndorsements) {
        try {
          GroupManager.updateGroupSendEndorsements(context, groupRecord.get().requireV2GroupProperties().getGroupMasterKey());

          groupSendEndorsementExpiration = GenZappDatabase.groups().getGroupSendEndorsementsExpiration(groupId);
          groupSendEndorsementRecords    = GenZappDatabase.groups().getGroupSendEndorsements(groupId);
        } catch (GroupChangeException | IOException e) {
          if (groupSendEndorsementExpiration == 0) {
            Log.w(TAG, "Unable to update group send endorsements, falling back to access key", e);
            useGroupSendEndorsements = false;
            groupSendEndorsementRecords = new GroupSendEndorsementRecords(Collections.emptyMap());
          } else {
            Log.w(TAG, "Unable to update group send endorsements, using what we have", e);
          }
        }

        Log.d(TAG, "Refresh all group state because we needed to refresh gse");
        AppDependencies.getJobManager().add(new RequestGroupV2InfoJob(groupId));
      }
    }

    List<Recipient> senderKeyTargets = new LinkedList<>();
    List<Recipient> legacyTargets    = new LinkedList<>();

    for (Recipient recipient : registeredTargets) {
      Optional<UnidentifiedAccess> access          = recipients.getAccessPair(recipient.getId());
      boolean                      validMembership = groupId == null || (groupRecord.isPresent() && groupRecord.get().getMembers().contains(recipient.getId()));

      if (useGroupSendEndorsements) {
        GroupSendEndorsement groupSendEndorsement = groupSendEndorsementRecords.getEndorsement(recipient.getId());
        if (groupSendEndorsement != null && recipient.getHasAci() && validMembership) {
          senderKeyTargets.add(recipient);
        } else {
          legacyTargets.add(recipient);
          if (validMembership) {
            Log.w(TAG, "Should be using group send endorsement but not found for " + recipient.getId());
            if (RemoteConfig.internalUser()) {
              GroupSendEndorsementInternalNotifier.postMissingGroupSendEndorsement(context);
            }
          }
        }
      } else {
        // Use sender key
        if (recipient.getHasServiceId() &&
            access.isPresent() &&
            validMembership)
        {
          senderKeyTargets.add(recipient);
        } else {
          legacyTargets.add(recipient);
        }
      }
    }

    if (distributionId == null) {
      Log.i(TAG, "No DistributionId. Using legacy.");
      legacyTargets.addAll(senderKeyTargets);
      senderKeyTargets.clear();
    } else if (isStorySend) {
      Log.i(TAG, "Sending a story. Using sender key for all " + allTargets.size() + " recipients.");
      senderKeyTargets.clear();
      senderKeyTargets.addAll(registeredTargets);
      legacyTargets.clear();
    } else if (GenZappStore.internal().removeSenderKeyMinimum()) {
      Log.i(TAG, "Sender key minimum removed. Using for " + senderKeyTargets.size() + " recipients.");
    } else if (senderKeyTargets.size() < 2) {
      Log.i(TAG, "Too few sender-key-capable users (" + senderKeyTargets.size() + "). Doing all legacy sends.");
      legacyTargets.addAll(senderKeyTargets);
      senderKeyTargets.clear();
    } else {
      Log.i(TAG, "Can use sender key for " + senderKeyTargets.size() + "/" + allTargets.size() + " recipients.");
    }

    if (relatedMessageId != null && groupId != null) {
      GenZappLocalMetrics.GroupMessageSend.onSenderKeyStarted(relatedMessageId.getId());
    }

    List<SendMessageResult>    allResults    = new ArrayList<>(allTargets.size());
    GenZappServiceMessageSender messageSender = AppDependencies.getGenZappServiceMessageSender();

    if (Util.hasItems(senderKeyTargets) && distributionId != null) {
      long           keyCreateTime  = SenderKeyUtil.getCreateTimeForOurKey(distributionId);
      long           keyAge         = System.currentTimeMillis() - keyCreateTime;

      if (keyCreateTime != -1 && keyAge > RemoteConfig.senderKeyMaxAge()) {
        Log.w(TAG, "DistributionId " + distributionId + " was created at " + keyCreateTime + " and is " + (keyAge) + " ms old (~" + TimeUnit.MILLISECONDS.toDays(keyAge) + " days). Rotating.");
        SenderKeyUtil.rotateOurKey(distributionId);
      }

      try {
        List<GenZappServiceAddress>               targets               = new ArrayList<>(senderKeyTargets.size());
        List<UnidentifiedAccess>                 access                = new ArrayList<>(senderKeyTargets.size());
        Map<ServiceId.ACI, GroupSendEndorsement> senderKeyEndorsements = new HashMap<>(senderKeyTargets.size());
        GroupSendEndorsements                    groupSendEndorsements = null;

        for (Recipient recipient : senderKeyTargets) {
          targets.add(recipients.getAddress(recipient.getId()));

          if (useGroupSendEndorsements) {
            senderKeyEndorsements.put(recipient.requireAci(), groupSendEndorsementRecords.getEndorsement(recipient.getId()));
            access.add(recipients.getAccess(recipient.getId()));
          } else {
            access.add(recipients.requireAccess(recipient.getId()));
          }
        }

        if (useGroupSendEndorsements) {
          groupSendEndorsements = new GroupSendEndorsements(
              groupSendEndorsementExpiration,
              senderKeyEndorsements,
              senderCertificate,
              GroupSecretParams.deriveFromMasterKey(groupRecord.get().requireV2GroupProperties().getGroupMasterKey())
          );
        }

        final MessageSendLogTables messageLogDatabase  = GenZappDatabase.messageLog();
        final AtomicLong           entryId             = new AtomicLong(-1);
        final boolean              includeInMessageLog = sendOperation.shouldIncludeInMessageLog();

        List<SendMessageResult> results = sendOperation.sendWithSenderKey(messageSender, distributionId, targets, access, groupSendEndorsements, isRecipientUpdate, partialResults -> {
          if (!includeInMessageLog) {
            return;
          }

          synchronized (entryId) {
            if (entryId.get() == -1) {
              entryId.set(messageLogDatabase.insertIfPossible(sendOperation.getSentTimestamp(), senderKeyTargets, partialResults, sendOperation.getContentHint(), sendOperation.getRelatedMessageId(), sendOperation.isUrgent()));
            } else {
              for (SendMessageResult result : partialResults) {
                entryId.set(messageLogDatabase.addRecipientToExistingEntryIfPossible(entryId.get(), recipients.requireRecipientId(result.getAddress()), sendOperation.getSentTimestamp(), result, sendOperation.getContentHint(), sendOperation.getRelatedMessageId(), sendOperation.isUrgent()));
              }
            }
          }
        });

        allResults.addAll(results);

        int successCount = (int) results.stream().filter(SendMessageResult::isSuccess).count();
        Log.d(TAG, "Successfully sent using sender key to " + successCount + "/" + targets.size() + " sender key targets.");

        if (relatedMessageId != null) {
          GenZappLocalMetrics.GroupMessageSend.onSenderKeyMslInserted(relatedMessageId.getId());
        }
      } catch (InvalidUnidentifiedAccessHeaderException e) {
        Log.w(TAG, "Someone had a bad UD header. Falling back to legacy sends.", e);
        legacyTargets.addAll(senderKeyTargets);

        if (useGroupSendEndorsements && RemoteConfig.internalUser()) {
          GroupSendEndorsementInternalNotifier.postGroupSendFallbackError(context);
        }
      } catch (NoSessionException e) {
        Log.w(TAG, "No session. Falling back to legacy sends.", e);
        legacyTargets.addAll(senderKeyTargets);
      } catch (InvalidKeyException e) {
        Log.w(TAG, "Invalid key. Falling back to legacy sends.", e);
        legacyTargets.addAll(senderKeyTargets);
      } catch (InvalidRegistrationIdException e) {
        Log.w(TAG, "Invalid registrationId. Falling back to legacy sends.", e);
        legacyTargets.addAll(senderKeyTargets);
      } catch (NotFoundException e) {
        Log.w(TAG, "Someone was unregistered. Falling back to legacy sends.", e);
        legacyTargets.addAll(senderKeyTargets);
      }
    } else if (relatedMessageId != null) {
      GenZappLocalMetrics.GroupMessageSend.onSenderKeyShared(relatedMessageId.getId());
      GenZappLocalMetrics.GroupMessageSend.onSenderKeyEncrypted(relatedMessageId.getId());
      GenZappLocalMetrics.GroupMessageSend.onSenderKeyMessageSent(relatedMessageId.getId());
      GenZappLocalMetrics.GroupMessageSend.onSenderKeySyncSent(relatedMessageId.getId());
      GenZappLocalMetrics.GroupMessageSend.onSenderKeyMslInserted(relatedMessageId.getId());
    }

    if (cancelationGenZapp != null && cancelationGenZapp.isCanceled()) {
      throw new CancelationException();
    }

    boolean onlyTargetIsSelfWithLinkedDevice = legacyTargets.isEmpty() && senderKeyTargets.isEmpty() && TextSecurePreferences.isMultiDevice(context);

    if (legacyTargets.size() > 0 || onlyTargetIsSelfWithLinkedDevice) {
      if (legacyTargets.size() > 0) {
        Log.i(TAG, "Need to do " + legacyTargets.size() + " legacy sends.");
      } else {
        Log.i(TAG, "Need to do a legacy send to send a sync message for a group of only ourselves.");
      }

      List<GenZappServiceAddress> legacyTargetAddresses = legacyTargets.stream().map(r -> recipients.getAddress(r.getId())).collect(Collectors.toList());
      List<UnidentifiedAccess>   legacyTargetAccesses  = legacyTargets.stream().map(r -> recipients.getAccess(r.getId())).collect(Collectors.toList());
      List<GroupSendFullToken>   groupSendTokens       = null;
      boolean                    recipientUpdate       = isRecipientUpdate || allResults.size() > 0;

      if (useGroupSendEndorsements) {
        Instant           expiration        = Instant.ofEpochMilli(groupSendEndorsementExpiration);
        GroupSecretParams groupSecretParams = GroupSecretParams.deriveFromMasterKey(groupRecord.get().requireV2GroupProperties().getGroupMasterKey());

        groupSendTokens = new ArrayList<>(legacyTargetAddresses.size());

        for (Recipient r : legacyTargets) {
          GroupSendEndorsement endorsement = groupSendEndorsementRecords.getEndorsement(r.getId());
          if (r.getHasAci() && endorsement != null) {
            groupSendTokens.add(endorsement.toFullToken(groupSecretParams, expiration));
          } else {
            groupSendTokens.add(null);
          }
        }
      }

      final MessageSendLogTables messageLogDatabase  = GenZappDatabase.messageLog();
      final AtomicLong           entryId             = new AtomicLong(-1);
      final boolean              includeInMessageLog = sendOperation.shouldIncludeInMessageLog();

      List<SendMessageResult> results = sendOperation.sendLegacy(messageSender, legacyTargetAddresses, legacyTargets, SealedSenderAccess.forFanOutGroupSend(groupSendTokens, SealedSenderAccessUtil.getSealedSenderCertificate(), legacyTargetAccesses), recipientUpdate, result -> {
        if (!includeInMessageLog) {
          return;
        }

        synchronized (entryId) {
          if (entryId.get() == -1) {
            entryId.set(messageLogDatabase.insertIfPossible(recipients.requireRecipientId(result.getAddress()), sendOperation.getSentTimestamp(), result, sendOperation.getContentHint(), sendOperation.getRelatedMessageId(), sendOperation.isUrgent()));
          } else {
            entryId.set(messageLogDatabase.addRecipientToExistingEntryIfPossible(entryId.get(), recipients.requireRecipientId(result.getAddress()), sendOperation.getSentTimestamp(), result, sendOperation.getContentHint(), sendOperation.getRelatedMessageId(), sendOperation.isUrgent()));
          }
        }
      }, cancelationGenZapp);

      allResults.addAll(results);

      int successCount = (int) results.stream().filter(SendMessageResult::isSuccess).count();
      Log.d(TAG, "Successfully sent using 1:1 to " + successCount + "/" + legacyTargetAddresses.size() + " legacy targets.");
    } else if (relatedMessageId != null) {
      GenZappLocalMetrics.GroupMessageSend.onLegacyMessageSent(relatedMessageId.getId());
      GenZappLocalMetrics.GroupMessageSend.onLegacySyncFinished(relatedMessageId.getId());
    }

    if (unregisteredTargets.size() > 0) {
      Log.w(TAG, "There are " + unregisteredTargets.size() + " unregistered targets. Including failure results.");

      List<SendMessageResult> unregisteredResults = unregisteredTargets.stream()
                                                                       .filter(Recipient::getHasServiceId)
                                                                       .map(t -> SendMessageResult.unregisteredFailure(new GenZappServiceAddress(t.requireServiceId(), t.getE164().orElse(null))))
                                                                       .collect(Collectors.toList());

      if (unregisteredResults.size() < unregisteredTargets.size()) {
        Log.w(TAG, "There are " + (unregisteredTargets.size() - unregisteredResults.size()) + " targets that have no UUID! Cannot report a failure for them.");
      }

      allResults.addAll(unregisteredResults);
    }

    return allResults;
  }

  private static @Nullable DistributionId getDistributionId(@Nullable GroupId.V2 groupId) {
    if (groupId != null) {
      return GenZappDatabase.groups().getOrCreateDistributionId(groupId);
    } else {
      return null;
    }
  }

  private static @Nullable DistributionId getDistributionId(@Nullable DistributionListId distributionListId) {
    if (distributionListId != null) {
      return Optional.ofNullable(GenZappDatabase.distributionLists().getDistributionId(distributionListId)).orElse(null);
    } else {
      return null;
    }
  }

  /** Abstraction layer to handle the different types of message send operations we can do */
  private interface SendOperation {
    @NonNull List<SendMessageResult> sendWithSenderKey(@NonNull GenZappServiceMessageSender messageSender,
                                                       @NonNull DistributionId distributionId,
                                                       @NonNull List<GenZappServiceAddress> targets,
                                                       @NonNull List<UnidentifiedAccess> access,
                                                       @Nullable GroupSendEndorsements groupSendEndorsements,
                                                       boolean isRecipientUpdate,
                                                       @Nullable PartialSendBatchCompleteListener partialListener)
        throws NoSessionException, UntrustedIdentityException, InvalidKeyException, IOException, InvalidRegistrationIdException;

    @NonNull List<SendMessageResult> sendLegacy(@NonNull GenZappServiceMessageSender messageSender,
                                                @NonNull List<GenZappServiceAddress> targets,
                                                @NonNull List<Recipient> targetRecipients,
                                                @NonNull List<SealedSenderAccess> sealedSenderAccesses,
                                                boolean isRecipientUpdate,
                                                @Nullable PartialSendCompleteListener partialListener,
                                                @Nullable CancelationGenZapp cancelationGenZapp)
        throws IOException, UntrustedIdentityException;

    @NonNull ContentHint getContentHint();
    long getSentTimestamp();
    boolean shouldIncludeInMessageLog();
    @NonNull MessageId getRelatedMessageId();
    boolean isUrgent();
  }

  private static class DataSendOperation implements SendOperation {
    private final GenZappServiceDataMessage message;
    private final ContentHint              contentHint;
    private final MessageId                relatedMessageId;
    private final boolean                  resendable;
    private final boolean                  urgent;
    private final boolean                  isForStory;
    private final GenZappServiceEditMessage editMessage;

    public static DataSendOperation resendable(@NonNull GenZappServiceDataMessage message, @NonNull ContentHint contentHint, @NonNull MessageId relatedMessageId, boolean urgent, boolean isForStory, @Nullable GenZappServiceEditMessage editMessage) {
      return new DataSendOperation(editMessage != null ? editMessage.getDataMessage() : message, contentHint, true, relatedMessageId, urgent, isForStory, editMessage);
    }

    public static DataSendOperation unresendable(@NonNull GenZappServiceDataMessage message, @NonNull ContentHint contentHint, boolean urgent) {
      return new DataSendOperation(message, contentHint, false, null, urgent, false, null);
    }

    private DataSendOperation(@NonNull GenZappServiceDataMessage message, @NonNull ContentHint contentHint, boolean resendable, @Nullable MessageId relatedMessageId, boolean urgent, boolean isForStory, @Nullable GenZappServiceEditMessage editMessage) {
      this.message          = message;
      this.contentHint      = contentHint;
      this.resendable       = resendable;
      this.relatedMessageId = relatedMessageId;
      this.urgent           = urgent;
      this.isForStory       = isForStory;
      this.editMessage      = editMessage;

      if (resendable && relatedMessageId == null) {
        throw new IllegalArgumentException("If a message is resendable, it must have a related message ID!");
      }
    }

    @Override
    public @NonNull List<SendMessageResult> sendWithSenderKey(@NonNull GenZappServiceMessageSender messageSender,
                                                              @NonNull DistributionId distributionId,
                                                              @NonNull List<GenZappServiceAddress> targets,
                                                              @NonNull List<UnidentifiedAccess> access,
                                                              @Nullable GroupSendEndorsements groupSendEndorsements,
                                                              boolean isRecipientUpdate,
                                                              @Nullable PartialSendBatchCompleteListener partialListener)
        throws NoSessionException, UntrustedIdentityException, InvalidKeyException, IOException, InvalidRegistrationIdException
    {
      SenderKeyGroupEvents listener = relatedMessageId != null ? new SenderKeyMetricEventListener(relatedMessageId.getId()) : SenderKeyGroupEvents.EMPTY;
      return messageSender.sendGroupDataMessage(distributionId, targets, access, groupSendEndorsements, isRecipientUpdate, contentHint, message, listener, urgent, isForStory, editMessage, partialListener);
    }

    @Override
    public @NonNull List<SendMessageResult> sendLegacy(@NonNull GenZappServiceMessageSender messageSender,
                                                       @NonNull List<GenZappServiceAddress> targets,
                                                       @NonNull List<Recipient> targetRecipients,
                                                       @NonNull List<SealedSenderAccess> sealedSenderAccesses,
                                                       boolean isRecipientUpdate,
                                                       @Nullable PartialSendCompleteListener partialListener,
                                                       @Nullable CancelationGenZapp cancelationGenZapp)
        throws IOException, UntrustedIdentityException
    {
      // PniSignatures are only needed for 1:1 messages, but some message jobs use the GroupSendUtil methods to send 1:1
      if (targets.size() == 1 && relatedMessageId == null) {
        Recipient          targetRecipient    = targetRecipients.get(0);
        SealedSenderAccess sealedSenderAccess = sealedSenderAccesses.get(0);
        SendMessageResult  result;

        if (editMessage != null) {
          result = messageSender.sendEditMessage(targets.get(0), sealedSenderAccess, contentHint, message, GenZappServiceMessageSender.IndividualSendEvents.EMPTY, urgent, editMessage.getTargetSentTimestamp());
        } else {
          result = messageSender.sendDataMessage(targets.get(0), sealedSenderAccess, contentHint, message, GenZappServiceMessageSender.IndividualSendEvents.EMPTY, urgent, targetRecipient.getNeedsPniSignature());
        }

        if (targetRecipient.getNeedsPniSignature()) {
          GenZappDatabase.pendingPniSignatureMessages().insertIfNecessary(targetRecipients.get(0).getId(), getSentTimestamp(), result);
        }

        return Collections.singletonList(result);
      } else {
        LegacyGroupEvents listener = relatedMessageId != null ? new LegacyMetricEventListener(relatedMessageId.getId()) : LegacyGroupEvents.EMPTY;

        if (editMessage != null) {
          return messageSender.sendEditMessage(targets, sealedSenderAccesses, isRecipientUpdate, contentHint, message, listener, partialListener, cancelationGenZapp, urgent, editMessage.getTargetSentTimestamp());
        } else {
          return messageSender.sendDataMessage(targets, sealedSenderAccesses, isRecipientUpdate, contentHint, message, listener, partialListener, cancelationGenZapp, urgent);
        }
      }
    }

    @Override
    public @NonNull ContentHint getContentHint() {
      return contentHint;
    }

    @Override
    public long getSentTimestamp() {
      return message.getTimestamp();
    }

    @Override
    public boolean shouldIncludeInMessageLog() {
      return resendable;
    }

    @Override
    public @NonNull MessageId getRelatedMessageId() {
      if (relatedMessageId != null) {
        return relatedMessageId;
      } else {
        throw new UnsupportedOperationException();
      }
    }

    @Override
    public boolean isUrgent() {
      return urgent;
    }
  }

  private static class TypingSendOperation implements SendOperation {

    private final GenZappServiceTypingMessage message;

    private TypingSendOperation(@NonNull GenZappServiceTypingMessage message) {
      this.message = message;
    }

    @Override
    public @NonNull List<SendMessageResult> sendWithSenderKey(@NonNull GenZappServiceMessageSender messageSender,
                                                              @NonNull DistributionId distributionId,
                                                              @NonNull List<GenZappServiceAddress> targets,
                                                              @NonNull List<UnidentifiedAccess> access,
                                                              @Nullable GroupSendEndorsements groupSendEndorsements,
                                                              boolean isRecipientUpdate,
                                                              @Nullable PartialSendBatchCompleteListener partialListener)
        throws NoSessionException, UntrustedIdentityException, InvalidKeyException, IOException, InvalidRegistrationIdException
    {
      messageSender.sendGroupTyping(distributionId, targets, access, groupSendEndorsements, message);
      List<SendMessageResult> results = targets.stream().map(a -> SendMessageResult.success(a, Collections.emptyList(), true, false, -1, Optional.empty())).collect(Collectors.toList());

      if (partialListener != null) {
        partialListener.onPartialSendComplete(results);
      }

      return results;
    }

    @Override
    public @NonNull List<SendMessageResult> sendLegacy(@NonNull GenZappServiceMessageSender messageSender,
                                                       @NonNull List<GenZappServiceAddress> targets,
                                                       @NonNull List<Recipient> targetRecipients,
                                                       @NonNull List<SealedSenderAccess> sealedSenderAccesses,
                                                       boolean isRecipientUpdate,
                                                       @Nullable PartialSendCompleteListener partialListener,
                                                       @Nullable CancelationGenZapp cancelationGenZapp)
        throws IOException
    {
      messageSender.sendTyping(targets, sealedSenderAccesses, message, cancelationGenZapp);
      return targets.stream().map(a -> SendMessageResult.success(a, Collections.emptyList(), true, false, -1, Optional.empty())).collect(Collectors.toList());
    }

    @Override
    public @NonNull ContentHint getContentHint() {
      return ContentHint.IMPLICIT;
    }

    @Override
    public long getSentTimestamp() {
      return message.getTimestamp();
    }

    @Override
    public boolean shouldIncludeInMessageLog() {
      return false;
    }

    @Override
    public @NonNull MessageId getRelatedMessageId() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isUrgent() {
      return false;
    }
  }

  private static class CallSendOperation implements SendOperation {

    private final GenZappServiceCallMessage message;

    private CallSendOperation(@NonNull GenZappServiceCallMessage message) {
      this.message = message;
    }

    @Override
    public @NonNull List<SendMessageResult> sendWithSenderKey(@NonNull GenZappServiceMessageSender messageSender,
                                                              @NonNull DistributionId distributionId,
                                                              @NonNull List<GenZappServiceAddress> targets,
                                                              @NonNull List<UnidentifiedAccess> access,
                                                              @Nullable GroupSendEndorsements groupSendEndorsements,
                                                              boolean isRecipientUpdate,
                                                              @Nullable PartialSendBatchCompleteListener partialSendListener)
        throws NoSessionException, UntrustedIdentityException, InvalidKeyException, IOException, InvalidRegistrationIdException
    {
      return messageSender.sendCallMessage(distributionId, targets, access, groupSendEndorsements, message, partialSendListener);
    }

    @Override
    public @NonNull List<SendMessageResult> sendLegacy(@NonNull GenZappServiceMessageSender messageSender,
                                                       @NonNull List<GenZappServiceAddress> targets,
                                                       @NonNull List<Recipient> targetRecipients,
                                                       @NonNull List<SealedSenderAccess> sealedSenderAccesses,
                                                       boolean isRecipientUpdate,
                                                       @Nullable PartialSendCompleteListener partialListener,
                                                       @Nullable CancelationGenZapp cancelationGenZapp)
        throws IOException
    {
      return messageSender.sendCallMessage(targets, sealedSenderAccesses, message);
    }

    @Override
    public @NonNull ContentHint getContentHint() {
      return ContentHint.IMPLICIT;
    }

    @Override
    public long getSentTimestamp() {
      return message.getTimestamp().get();
    }

    @Override
    public boolean shouldIncludeInMessageLog() {
      return false;
    }

    @Override
    public @NonNull MessageId getRelatedMessageId() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isUrgent() {
      return message.isUrgent();
    }
  }

  public static class StorySendOperation implements SendOperation {

    private final MessageId                               relatedMessageId;
    private final GroupId                                 groupId;
    private final long                                    sentTimestamp;
    private final GenZappServiceStoryMessage               message;
    private final Set<GenZappServiceStoryMessageRecipient> manifest;

    public StorySendOperation(@NonNull MessageId relatedMessageId,
                              @Nullable GroupId groupId,
                              long sentTimestamp,
                              @NonNull GenZappServiceStoryMessage message,
                              @NonNull Set<GenZappServiceStoryMessageRecipient> manifest)
    {
      this.relatedMessageId = relatedMessageId;
      this.groupId          = groupId;
      this.sentTimestamp    = sentTimestamp;
      this.message          = message;
      this.manifest         = manifest;
    }

    @Override
    public @NonNull List<SendMessageResult> sendWithSenderKey(@NonNull GenZappServiceMessageSender messageSender,
                                                              @NonNull DistributionId distributionId,
                                                              @NonNull List<GenZappServiceAddress> targets,
                                                              @NonNull List<UnidentifiedAccess> access,
                                                              @Nullable GroupSendEndorsements groupSendEndorsements,
                                                              boolean isRecipientUpdate,
                                                              @Nullable PartialSendBatchCompleteListener partialListener)
        throws NoSessionException, UntrustedIdentityException, InvalidKeyException, IOException, InvalidRegistrationIdException
    {
      return messageSender.sendGroupStory(distributionId, Optional.ofNullable(groupId).map(GroupId::getDecodedId), targets, access, groupSendEndorsements, isRecipientUpdate, message, getSentTimestamp(), manifest, partialListener);
    }

    @Override
    public @NonNull List<SendMessageResult> sendLegacy(@NonNull GenZappServiceMessageSender messageSender,
                                                       @NonNull List<GenZappServiceAddress> targets,
                                                       @NonNull List<Recipient> targetRecipients,
                                                       @NonNull List<SealedSenderAccess> sealedSenderAccesses,
                                                       boolean isRecipientUpdate,
                                                       @Nullable PartialSendCompleteListener partialListener,
                                                       @Nullable CancelationGenZapp cancelationGenZapp)
        throws IOException, UntrustedIdentityException
    {
      // We only allow legacy sends if you're sending to an empty group and just need to send a sync message.
      if (targets.isEmpty()) {
        Log.w(TAG, "Only sending a sync message.");
        messageSender.sendStorySyncMessage(message, getSentTimestamp(), isRecipientUpdate, manifest);
        return Collections.emptyList();
      } else {
        throw new UnsupportedOperationException("Stories can only be send via sender key!");
      }
    }

    @Override
    public @NonNull ContentHint getContentHint() {
      return ContentHint.IMPLICIT;
    }

    @Override
    public long getSentTimestamp() {
      return sentTimestamp;
    }

    @Override
    public boolean shouldIncludeInMessageLog() {
      return true;
    }

    @Override
    public @NonNull MessageId getRelatedMessageId() {
      return relatedMessageId;
    }

    @Override
    public boolean isUrgent() {
      return false;
    }
  }

  private static final class SenderKeyMetricEventListener implements SenderKeyGroupEvents {

    private final long messageId;

    private SenderKeyMetricEventListener(long messageId) {
      this.messageId = messageId;
    }

    @Override
    public void onSenderKeyShared() {
      GenZappLocalMetrics.GroupMessageSend.onSenderKeyShared(messageId);
    }

    @Override
    public void onMessageEncrypted() {
      GenZappLocalMetrics.GroupMessageSend.onSenderKeyEncrypted(messageId);
    }

    @Override
    public void onMessageSent() {
      GenZappLocalMetrics.GroupMessageSend.onSenderKeyMessageSent(messageId);
    }

    @Override
    public void onSyncMessageSent() {
      GenZappLocalMetrics.GroupMessageSend.onSenderKeySyncSent(messageId);
    }
  }

  private static final class LegacyMetricEventListener implements LegacyGroupEvents {

    private final long messageId;

    private LegacyMetricEventListener(long messageId) {
      this.messageId = messageId;
    }

    @Override
    public void onMessageEncrypted() {}

    @Override
    public void onMessageSent() {
      GenZappLocalMetrics.GroupMessageSend.onLegacyMessageSent(messageId);
    }

    @Override
    public void onSyncMessageSent() {
      GenZappLocalMetrics.GroupMessageSend.onLegacySyncFinished(messageId);
    }
  }

  /**
   * Little utility wrapper that lets us get the various different slices of recipient models that we need for different methods.
   */
  private static final class RecipientData {

    private final Map<RecipientId, Optional<UnidentifiedAccess>> accessById;
    private final Map<RecipientId, GenZappServiceAddress>             addressById;
    private final RecipientAccessList                                accessList;

    RecipientData(@NonNull Context context, @NonNull List<Recipient> recipients, boolean isForStory) throws IOException {
      this.accessById  = SealedSenderAccessUtil.getAccessMapFor(recipients, isForStory);
      this.addressById = mapAddresses(context, recipients);
      this.accessList  = new RecipientAccessList(recipients);
    }

    @NonNull GenZappServiceAddress getAddress(@NonNull RecipientId id) {
      return Objects.requireNonNull(addressById.get(id));
    }

    @NonNull Optional<UnidentifiedAccess> getAccessPair(@NonNull RecipientId id) {
      return Objects.requireNonNull(accessById.get(id));
    }

    @Nullable UnidentifiedAccess getAccess(@NonNull RecipientId id) {
      return Objects.requireNonNull(accessById.get(id)).orElse(null);
    }

    @NonNull UnidentifiedAccess requireAccess(@NonNull RecipientId id) {
      return Objects.requireNonNull(accessById.get(id)).get();
    }

    @NonNull RecipientId requireRecipientId(@NonNull GenZappServiceAddress address) {
      return accessList.requireIdByAddress(address);
    }

    private static @NonNull Map<RecipientId, GenZappServiceAddress> mapAddresses(@NonNull Context context, @NonNull List<Recipient> recipients) throws IOException {
      List<GenZappServiceAddress> addresses = RecipientUtil.toGenZappServiceAddressesFromResolved(context, recipients);

      Iterator<Recipient>            recipientIterator = recipients.iterator();
      Iterator<GenZappServiceAddress> addressIterator   = addresses.iterator();

      Map<RecipientId, GenZappServiceAddress> map = new HashMap<>(recipients.size());

      while (recipientIterator.hasNext()) {
        map.put(recipientIterator.next().getId(), addressIterator.next());
      }

      return map;
    }
  }
}
