package org.thoughtcrime.securesms.storage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.annimon.stream.Stream;

import org.GenZapp.libGenZapp.zkgroup.groups.GroupMasterKey;
import org.thoughtcrime.securesms.components.settings.app.usernamelinks.UsernameQrCodeColorScheme;
import org.thoughtcrime.securesms.database.GroupTable;
import org.thoughtcrime.securesms.database.IdentityTable;
import org.thoughtcrime.securesms.database.RecipientTable;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.database.model.DistributionListId;
import org.thoughtcrime.securesms.database.model.DistributionListRecord;
import org.thoughtcrime.securesms.database.model.InAppPaymentSubscriberRecord;
import org.thoughtcrime.securesms.database.model.RecipientRecord;
import org.thoughtcrime.securesms.database.model.databaseprotos.InAppPaymentData;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.keyvalue.PhoneNumberPrivacyValues;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.GenZappservice.api.push.GenZappServiceAddress;
import org.whispersystems.GenZappservice.api.storage.GenZappAccountRecord;
import org.whispersystems.GenZappservice.api.storage.GenZappContactRecord;
import org.whispersystems.GenZappservice.api.storage.GenZappGroupV1Record;
import org.whispersystems.GenZappservice.api.storage.GenZappGroupV2Record;
import org.whispersystems.GenZappservice.api.storage.GenZappStorageRecord;
import org.whispersystems.GenZappservice.api.storage.GenZappStoryDistributionListRecord;
import org.whispersystems.GenZappservice.api.subscriptions.SubscriberId;
import org.whispersystems.GenZappservice.api.util.UuidUtil;
import org.whispersystems.GenZappservice.internal.storage.protos.AccountRecord;
import org.whispersystems.GenZappservice.internal.storage.protos.ContactRecord.IdentityState;
import org.whispersystems.GenZappservice.internal.storage.protos.GroupV2Record;

import java.util.Currency;
import java.util.List;
import java.util.stream.Collectors;

public final class StorageSyncModels {

  private StorageSyncModels() {}

  public static @NonNull GenZappStorageRecord localToRemoteRecord(@NonNull RecipientRecord settings) {
    if (settings.getStorageId() == null) {
      throw new AssertionError("Must have a storage key!");
    }

    return localToRemoteRecord(settings, settings.getStorageId());
  }

  public static @NonNull GenZappStorageRecord localToRemoteRecord(@NonNull RecipientRecord settings, @NonNull GroupMasterKey groupMasterKey) {
    if (settings.getStorageId() == null) {
      throw new AssertionError("Must have a storage key!");
    }

    return GenZappStorageRecord.forGroupV2(localToRemoteGroupV2(settings, settings.getStorageId(), groupMasterKey));
  }

  public static @NonNull GenZappStorageRecord localToRemoteRecord(@NonNull RecipientRecord settings, @NonNull byte[] rawStorageId) {
    switch (settings.getRecipientType()) {
      case INDIVIDUAL:        return GenZappStorageRecord.forContact(localToRemoteContact(settings, rawStorageId));
      case GV1:               return GenZappStorageRecord.forGroupV1(localToRemoteGroupV1(settings, rawStorageId));
      case GV2:               return GenZappStorageRecord.forGroupV2(localToRemoteGroupV2(settings, rawStorageId, settings.getSyncExtras().getGroupMasterKey()));
      case DISTRIBUTION_LIST: return GenZappStorageRecord.forStoryDistributionList(localToRemoteStoryDistributionList(settings, rawStorageId));
      default:                throw new AssertionError("Unsupported type!");
    }
  }

  public static AccountRecord.PhoneNumberSharingMode localToRemotePhoneNumberSharingMode(PhoneNumberPrivacyValues.PhoneNumberSharingMode phoneNumberPhoneNumberSharingMode) {
    switch (phoneNumberPhoneNumberSharingMode) {
      case DEFAULT  : return AccountRecord.PhoneNumberSharingMode.NOBODY;
      case EVERYBODY: return AccountRecord.PhoneNumberSharingMode.EVERYBODY;
      case NOBODY   : return AccountRecord.PhoneNumberSharingMode.NOBODY;
      default       : throw new AssertionError();
    }
  }

  public static PhoneNumberPrivacyValues.PhoneNumberSharingMode remoteToLocalPhoneNumberSharingMode(AccountRecord.PhoneNumberSharingMode phoneNumberPhoneNumberSharingMode) {
    switch (phoneNumberPhoneNumberSharingMode) {
      case EVERYBODY    : return PhoneNumberPrivacyValues.PhoneNumberSharingMode.EVERYBODY;
      case NOBODY       : return PhoneNumberPrivacyValues.PhoneNumberSharingMode.NOBODY;
      default           : return PhoneNumberPrivacyValues.PhoneNumberSharingMode.DEFAULT;
    }
  }

  public static List<GenZappAccountRecord.PinnedConversation> localToRemotePinnedConversations(@NonNull List<RecipientRecord> settings) {
    return Stream.of(settings)
                 .filter(s -> s.getRecipientType() == RecipientTable.RecipientType.GV1 ||
                              s.getRecipientType() == RecipientTable.RecipientType.GV2 ||
                              s.getRegistered() == RecipientTable.RegisteredState.REGISTERED)
                 .map(StorageSyncModels::localToRemotePinnedConversation)
                 .toList();
  }

  private static @NonNull GenZappAccountRecord.PinnedConversation localToRemotePinnedConversation(@NonNull RecipientRecord settings) {
    switch (settings.getRecipientType()) {
      case INDIVIDUAL: return GenZappAccountRecord.PinnedConversation.forContact(new GenZappServiceAddress(settings.getServiceId(), settings.getE164()));
      case GV1: return GenZappAccountRecord.PinnedConversation.forGroupV1(settings.getGroupId().requireV1().getDecodedId());
      case GV2: return GenZappAccountRecord.PinnedConversation.forGroupV2(settings.getSyncExtras().getGroupMasterKey().serialize());
      default       : throw new AssertionError("Unexpected group type!");
    }
  }

  public static @NonNull AccountRecord.UsernameLink.Color localToRemoteUsernameColor(UsernameQrCodeColorScheme local) {
    switch (local) {
      case Blue:   return AccountRecord.UsernameLink.Color.BLUE;
      case White:  return AccountRecord.UsernameLink.Color.WHITE;
      case Grey:   return AccountRecord.UsernameLink.Color.GREY;
      case Tan:    return AccountRecord.UsernameLink.Color.OLIVE;
      case Green:  return AccountRecord.UsernameLink.Color.GREEN;
      case Orange: return AccountRecord.UsernameLink.Color.ORANGE;
      case Pink:   return AccountRecord.UsernameLink.Color.PINK;
      case Purple: return AccountRecord.UsernameLink.Color.PURPLE;
      default:     return AccountRecord.UsernameLink.Color.BLUE;
    }
  }

  public static @NonNull UsernameQrCodeColorScheme remoteToLocalUsernameColor(AccountRecord.UsernameLink.Color remote) {
    switch (remote) {
      case BLUE:   return UsernameQrCodeColorScheme.Blue;
      case WHITE:  return UsernameQrCodeColorScheme.White;
      case GREY:   return UsernameQrCodeColorScheme.Grey;
      case OLIVE:  return UsernameQrCodeColorScheme.Tan;
      case GREEN:  return UsernameQrCodeColorScheme.Green;
      case ORANGE: return UsernameQrCodeColorScheme.Orange;
      case PINK:   return UsernameQrCodeColorScheme.Pink;
      case PURPLE: return UsernameQrCodeColorScheme.Purple;
      default:     return UsernameQrCodeColorScheme.Blue;
    }
  }

  private static @NonNull GenZappContactRecord localToRemoteContact(@NonNull RecipientRecord recipient, byte[] rawStorageId) {
    if (recipient.getAci() == null && recipient.getPni() == null && recipient.getE164() == null) {
      throw new AssertionError("Must have either a UUID or a phone number!");
    }

    boolean   hideStory = recipient.getExtras() != null && recipient.getExtras().hideStory();

    return new GenZappContactRecord.Builder(rawStorageId, recipient.getAci(), recipient.getSyncExtras().getStorageProto())
                                  .setE164(recipient.getE164())
                                  .setPni(recipient.getPni())
                                  .setProfileKey(recipient.getProfileKey())
                                  .setProfileGivenName(recipient.getProfileName().getGivenName())
                                  .setProfileFamilyName(recipient.getProfileName().getFamilyName())
                                  .setSystemGivenName(recipient.getSystemProfileName().getGivenName())
                                  .setSystemFamilyName(recipient.getSystemProfileName().getFamilyName())
                                  .setSystemNickname(recipient.getSyncExtras().getSystemNickname())
                                  .setBlocked(recipient.isBlocked())
                                  .setProfileSharingEnabled(recipient.isProfileSharing() || recipient.getSystemContactUri() != null)
                                  .setIdentityKey(recipient.getSyncExtras().getIdentityKey())
                                  .setIdentityState(localToRemoteIdentityState(recipient.getSyncExtras().getIdentityStatus()))
                                  .setArchived(recipient.getSyncExtras().isArchived())
                                  .setForcedUnread(recipient.getSyncExtras().isForcedUnread())
                                  .setMuteUntil(recipient.getMuteUntil())
                                  .setHideStory(hideStory)
                                  .setUnregisteredTimestamp(recipient.getSyncExtras().getUnregisteredTimestamp())
                                  .setHidden(recipient.getHiddenState() != Recipient.HiddenState.NOT_HIDDEN)
                                  .setUsername(recipient.getUsername())
                                  .setPniSignatureVerified(recipient.getSyncExtras().getPniSignatureVerified())
                                  .setNicknameGivenName(recipient.getNickname().getGivenName())
                                  .setNicknameFamilyName(recipient.getNickname().getFamilyName())
                                  .setNote(recipient.getNote())
                                  .build();
  }

  private static @NonNull GenZappGroupV1Record localToRemoteGroupV1(@NonNull RecipientRecord recipient, byte[] rawStorageId) {
    GroupId groupId = recipient.getGroupId();

    if (groupId == null) {
      throw new AssertionError("Must have a groupId!");
    }

    if (!groupId.isV1()) {
      throw new AssertionError("Group is not V1");
    }

    return new GenZappGroupV1Record.Builder(rawStorageId, groupId.getDecodedId(), recipient.getSyncExtras().getStorageProto())
                                  .setBlocked(recipient.isBlocked())
                                  .setProfileSharingEnabled(recipient.isProfileSharing())
                                  .setArchived(recipient.getSyncExtras().isArchived())
                                  .setForcedUnread(recipient.getSyncExtras().isForcedUnread())
                                  .setMuteUntil(recipient.getMuteUntil())
                                  .build();
  }

  private static @NonNull GenZappGroupV2Record localToRemoteGroupV2(@NonNull RecipientRecord recipient, byte[] rawStorageId, @NonNull GroupMasterKey groupMasterKey) {
    GroupId groupId = recipient.getGroupId();

    if (groupId == null) {
      throw new AssertionError("Must have a groupId!");
    }

    if (!groupId.isV2()) {
      throw new AssertionError("Group is not V2");
    }

    if (groupMasterKey == null) {
      throw new AssertionError("Group master key not on recipient record");
    }

    boolean                     hideStory        = recipient.getExtras() != null && recipient.getExtras().hideStory();
    GroupTable.ShowAsStoryState showAsStoryState = GenZappDatabase.groups().getShowAsStoryState(groupId);
    GroupV2Record.StorySendMode storySendMode;

    switch (showAsStoryState) {
      case ALWAYS:
        storySendMode = GroupV2Record.StorySendMode.ENABLED;
        break;
      case NEVER:
        storySendMode = GroupV2Record.StorySendMode.DISABLED;
        break;
      default:
        storySendMode = GroupV2Record.StorySendMode.DEFAULT;
    }

    return new GenZappGroupV2Record.Builder(rawStorageId, groupMasterKey, recipient.getSyncExtras().getStorageProto())
                                  .setBlocked(recipient.isBlocked())
                                  .setProfileSharingEnabled(recipient.isProfileSharing())
                                  .setArchived(recipient.getSyncExtras().isArchived())
                                  .setForcedUnread(recipient.getSyncExtras().isForcedUnread())
                                  .setMuteUntil(recipient.getMuteUntil())
                                  .setNotifyForMentionsWhenMuted(recipient.getMentionSetting() == RecipientTable.MentionSetting.ALWAYS_NOTIFY)
                                  .setHideStory(hideStory)
                                  .setStorySendMode(storySendMode)
                                  .build();
  }

  private static @NonNull GenZappStoryDistributionListRecord localToRemoteStoryDistributionList(@NonNull RecipientRecord recipient, @NonNull byte[] rawStorageId) {
    DistributionListId distributionListId = recipient.getDistributionListId();

    if (distributionListId == null) {
      throw new AssertionError("Must have a distributionListId!");
    }

    DistributionListRecord record = GenZappDatabase.distributionLists().getListForStorageSync(distributionListId);
    if (record == null) {
      throw new AssertionError("Must have a distribution list record!");
    }

    if (record.getDeletedAtTimestamp() > 0L) {
      return new GenZappStoryDistributionListRecord.Builder(rawStorageId, recipient.getSyncExtras().getStorageProto())
                                                  .setIdentifier(UuidUtil.toByteArray(record.getDistributionId().asUuid()))
                                                  .setDeletedAtTimestamp(record.getDeletedAtTimestamp())
                                                  .build();
    }

    return new GenZappStoryDistributionListRecord.Builder(rawStorageId, recipient.getSyncExtras().getStorageProto())
                                                .setIdentifier(UuidUtil.toByteArray(record.getDistributionId().asUuid()))
                                                .setName(record.getName())
                                                .setRecipients(record.getMembersToSync()
                                                                     .stream()
                                                                     .map(Recipient::resolved)
                                                                     .filter(Recipient::getHasServiceId)
                                                                     .map(Recipient::requireServiceId)
                                                                     .map(GenZappServiceAddress::new)
                                                                     .collect(Collectors.toList()))
                                                .setAllowsReplies(record.getAllowsReplies())
                                                .setIsBlockList(record.getPrivacyMode().isBlockList())
                                                .build();
  }

  public static @NonNull IdentityTable.VerifiedStatus remoteToLocalIdentityStatus(@NonNull IdentityState identityState) {
    switch (identityState) {
      case VERIFIED:   return IdentityTable.VerifiedStatus.VERIFIED;
      case UNVERIFIED: return IdentityTable.VerifiedStatus.UNVERIFIED;
      default:         return IdentityTable.VerifiedStatus.DEFAULT;
    }
  }

  private static IdentityState localToRemoteIdentityState(@NonNull IdentityTable.VerifiedStatus local) {
    switch (local) {
      case VERIFIED:   return IdentityState.VERIFIED;
      case UNVERIFIED: return IdentityState.UNVERIFIED;
      default:         return IdentityState.DEFAULT;
    }
  }

  /**
   * TODO - need to store the subscriber type
   */
  public static @NonNull GenZappAccountRecord.Subscriber localToRemoteSubscriber(@Nullable InAppPaymentSubscriberRecord subscriber) {
    if (subscriber == null) {
      return new GenZappAccountRecord.Subscriber(null, null);
    } else {
      return new GenZappAccountRecord.Subscriber(subscriber.getCurrency().getCurrencyCode(), subscriber.getSubscriberId().getBytes());
    }
  }

  public static @Nullable InAppPaymentSubscriberRecord remoteToLocalSubscriber(
      @NonNull GenZappAccountRecord.Subscriber subscriber,
      @NonNull InAppPaymentSubscriberRecord.Type type
  ) {
    if (subscriber.getId().isPresent()) {
      SubscriberId                       subscriberId          = SubscriberId.fromBytes(subscriber.getId().get());
      InAppPaymentSubscriberRecord       localSubscriberRecord = GenZappDatabase.inAppPaymentSubscribers().getBySubscriberId(subscriberId);
      boolean                            requiresCancel        = localSubscriberRecord != null && localSubscriberRecord.getRequiresCancel();
      InAppPaymentData.PaymentMethodType paymentMethodType     = localSubscriberRecord != null ? localSubscriberRecord.getPaymentMethodType() : InAppPaymentData.PaymentMethodType.UNKNOWN;

      Currency currency;
      if (subscriber.getCurrencyCode().isEmpty()) {
        return null;
      } else {
        try {
          currency = Currency.getInstance(subscriber.getCurrencyCode().get());
        } catch (IllegalArgumentException e) {
          return null;
        }
      }

      return new InAppPaymentSubscriberRecord(subscriberId, currency, type, requiresCancel, paymentMethodType);
    } else {
      return null;
    }
  }
}
