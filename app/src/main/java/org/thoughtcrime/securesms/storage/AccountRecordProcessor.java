package org.thoughtcrime.securesms.storage;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.GenZapp.core.util.StringUtil;
import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.GenZappservice.api.storage.GenZappAccountRecord;
import org.whispersystems.GenZappservice.api.storage.GenZappAccountRecord.PinnedConversation;
import org.whispersystems.GenZappservice.api.util.OptionalUtil;
import org.whispersystems.GenZappservice.internal.storage.protos.AccountRecord;
import org.whispersystems.GenZappservice.internal.storage.protos.OptionalBool;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Processes {@link GenZappAccountRecord}s. Unlike some other {@link StorageRecordProcessor}s, this
 * one has some statefulness in order to reject all but one account record (since we should have
 * exactly one account record).
 */
public class AccountRecordProcessor extends DefaultStorageRecordProcessor<GenZappAccountRecord> {

  private static final String TAG = Log.tag(AccountRecordProcessor.class);

  private final Context             context;
  private final GenZappAccountRecord localAccountRecord;
  private final Recipient           self;

  private boolean foundAccountRecord = false;

  public AccountRecordProcessor(@NonNull Context context, @NonNull Recipient self) {
    this(context, self, StorageSyncHelper.buildAccountRecord(context, self).getAccount().get());
  }

  AccountRecordProcessor(@NonNull Context context, @NonNull Recipient self, @NonNull GenZappAccountRecord localAccountRecord) {
    this.context            = context;
    this.self               = self;
    this.localAccountRecord = localAccountRecord;
  }

  /**
   * We want to catch:
   * - Multiple account records
   */
  @Override
  boolean isInvalid(@NonNull GenZappAccountRecord remote) {
    if (foundAccountRecord) {
      Log.w(TAG, "Found an additional account record! Considering it invalid.");
      return true;
    }

    foundAccountRecord = true;
    return false;
  }

  @Override
  public @NonNull Optional<GenZappAccountRecord> getMatching(@NonNull GenZappAccountRecord record, @NonNull StorageKeyGenerator keyGenerator) {
    return Optional.of(localAccountRecord);
  }

  @Override
  public @NonNull GenZappAccountRecord merge(@NonNull GenZappAccountRecord remote, @NonNull GenZappAccountRecord local, @NonNull StorageKeyGenerator keyGenerator) {
    String givenName;
    String familyName;

    if (remote.getGivenName().isPresent() || remote.getFamilyName().isPresent()) {
      givenName  = remote.getGivenName().orElse("");
      familyName = remote.getFamilyName().orElse("");
    } else {
      givenName  = local.getGivenName().orElse("");
      familyName = local.getFamilyName().orElse("");
    }

    GenZappAccountRecord.Payments payments;

    if (remote.getPayments().getEntropy().isPresent()) {
      payments = remote.getPayments();
    } else {
      payments = local.getPayments();
    }

    GenZappAccountRecord.Subscriber subscriber;

    if (remote.getSubscriber().getId().isPresent()) {
      subscriber = remote.getSubscriber();
    } else {
      subscriber = local.getSubscriber();
    }

    OptionalBool storyViewReceiptsState;
    if (remote.getStoryViewReceiptsState() == OptionalBool.UNSET) {
      storyViewReceiptsState = local.getStoryViewReceiptsState();
    } else {
      storyViewReceiptsState = remote.getStoryViewReceiptsState();
    }

    byte[]                               unknownFields                 = remote.serializeUnknownFields();
    String                               avatarUrlPath                 = OptionalUtil.or(remote.getAvatarUrlPath(), local.getAvatarUrlPath()).orElse("");
    byte[]                               profileKey                    = OptionalUtil.or(remote.getProfileKey(), local.getProfileKey()).orElse(null);
    boolean                              noteToSelfArchived            = remote.isNoteToSelfArchived();
    boolean                              noteToSelfForcedUnread        = remote.isNoteToSelfForcedUnread();
    boolean                              readReceipts                  = remote.isReadReceiptsEnabled();
    boolean                              typingIndicators              = remote.isTypingIndicatorsEnabled();
    boolean                              sealedSenderIndicators        = remote.isSealedSenderIndicatorsEnabled();
    boolean                              linkPreviews                  = remote.isLinkPreviewsEnabled();
    boolean                              unlisted                      = remote.isPhoneNumberUnlisted();
    List<PinnedConversation>             pinnedConversations           = remote.getPinnedConversations();
    AccountRecord.PhoneNumberSharingMode phoneNumberSharingMode        = remote.getPhoneNumberSharingMode();
    boolean                              preferContactAvatars          = remote.isPreferContactAvatars();
    int                                  universalExpireTimer          = remote.getUniversalExpireTimer();
    boolean                              primarySendsSms               = GenZappStore.account().isPrimaryDevice() ? local.isPrimarySendsSms() : remote.isPrimarySendsSms();
    String                               e164                          = GenZappStore.account().isPrimaryDevice() ? local.getE164() : remote.getE164();
    List<String>                         defaultReactions              = remote.getDefaultReactions().size() > 0 ? remote.getDefaultReactions() : local.getDefaultReactions();
    boolean                              displayBadgesOnProfile        = remote.isDisplayBadgesOnProfile();
    boolean                              subscriptionManuallyCancelled = remote.isSubscriptionManuallyCancelled();
    boolean                              keepMutedChatsArchived        = remote.isKeepMutedChatsArchived();
    boolean                              hasSetMyStoriesPrivacy        = remote.hasSetMyStoriesPrivacy();
    boolean                              hasViewedOnboardingStory      = remote.hasViewedOnboardingStory() || local.hasViewedOnboardingStory();
    boolean                              storiesDisabled               = remote.isStoriesDisabled();
    boolean                              hasSeenGroupStoryEducation    = remote.hasSeenGroupStoryEducationSheet() || local.hasSeenGroupStoryEducationSheet();
    boolean                              hasSeenUsernameOnboarding     = remote.hasCompletedUsernameOnboarding() || local.hasCompletedUsernameOnboarding();
    String                               username                      = remote.getUsername();
    AccountRecord.UsernameLink           usernameLink                  = remote.getUsernameLink();
    boolean                              matchesRemote                 = doParamsMatch(remote, unknownFields, givenName, familyName, avatarUrlPath, profileKey, noteToSelfArchived, noteToSelfForcedUnread, readReceipts, typingIndicators, sealedSenderIndicators, linkPreviews, phoneNumberSharingMode, unlisted, pinnedConversations, preferContactAvatars, payments, universalExpireTimer, primarySendsSms, e164, defaultReactions, subscriber, displayBadgesOnProfile, subscriptionManuallyCancelled, keepMutedChatsArchived, hasSetMyStoriesPrivacy, hasViewedOnboardingStory, hasSeenUsernameOnboarding, storiesDisabled, storyViewReceiptsState, username, usernameLink);
    boolean                              matchesLocal                  = doParamsMatch(local, unknownFields, givenName, familyName, avatarUrlPath, profileKey, noteToSelfArchived, noteToSelfForcedUnread, readReceipts, typingIndicators, sealedSenderIndicators, linkPreviews, phoneNumberSharingMode, unlisted, pinnedConversations, preferContactAvatars, payments, universalExpireTimer, primarySendsSms, e164, defaultReactions, subscriber, displayBadgesOnProfile, subscriptionManuallyCancelled, keepMutedChatsArchived, hasSetMyStoriesPrivacy, hasViewedOnboardingStory, hasSeenUsernameOnboarding, storiesDisabled, storyViewReceiptsState, username, usernameLink);

    if (matchesRemote) {
      return remote;
    } else if (matchesLocal) {
      return local;
    } else {
      GenZappAccountRecord.Builder builder = new GenZappAccountRecord.Builder(keyGenerator.generate(), unknownFields)
                                                                   .setGivenName(givenName)
                                                                   .setFamilyName(familyName)
                                                                   .setAvatarUrlPath(avatarUrlPath)
                                                                   .setProfileKey(profileKey)
                                                                   .setNoteToSelfArchived(noteToSelfArchived)
                                                                   .setNoteToSelfForcedUnread(noteToSelfForcedUnread)
                                                                   .setReadReceiptsEnabled(readReceipts)
                                                                   .setTypingIndicatorsEnabled(typingIndicators)
                                                                   .setSealedSenderIndicatorsEnabled(sealedSenderIndicators)
                                                                   .setLinkPreviewsEnabled(linkPreviews)
                                                                   .setUnlistedPhoneNumber(unlisted)
                                                                   .setPhoneNumberSharingMode(phoneNumberSharingMode)
                                                                   .setUnlistedPhoneNumber(unlisted)
                                                                   .setPinnedConversations(pinnedConversations)
                                                                   .setPreferContactAvatars(preferContactAvatars)
                                                                   .setPayments(payments.isEnabled(), payments.getEntropy().orElse(null))
                                                                   .setUniversalExpireTimer(universalExpireTimer)
                                                                   .setPrimarySendsSms(primarySendsSms)
                                                                   .setDefaultReactions(defaultReactions)
                                                                   .setSubscriber(subscriber)
                                                                   .setStoryViewReceiptsState(storyViewReceiptsState)
                                                                   .setDisplayBadgesOnProfile(displayBadgesOnProfile)
                                                                   .setSubscriptionManuallyCancelled(subscriptionManuallyCancelled)
                                                                   .setKeepMutedChatsArchived(keepMutedChatsArchived)
                                                                   .setHasSetMyStoriesPrivacy(hasSetMyStoriesPrivacy)
                                                                   .setHasViewedOnboardingStory(hasViewedOnboardingStory)
                                                                   .setStoriesDisabled(storiesDisabled)
                                                                   .setHasSeenGroupStoryEducationSheet(hasSeenGroupStoryEducation)
                                                                   .setHasCompletedUsernameOnboarding(hasSeenUsernameOnboarding)
                                                                   .setUsername(username)
                                                                   .setUsernameLink(usernameLink);

      return builder.build();
    }
  }

  @Override
  void insertLocal(@NonNull GenZappAccountRecord record) {
    throw new UnsupportedOperationException("We should always have a local AccountRecord, so we should never been inserting a new one.");
  }

  @Override
  void updateLocal(@NonNull StorageRecordUpdate<GenZappAccountRecord> update) {
    StorageSyncHelper.applyAccountStorageSyncUpdates(context, self, update, true);
  }

  @Override
  public int compare(@NonNull GenZappAccountRecord lhs, @NonNull GenZappAccountRecord rhs) {
    return 0;
  }

  private static boolean doParamsMatch(@NonNull GenZappAccountRecord contact,
                                       @Nullable byte[] unknownFields,
                                       @NonNull String givenName,
                                       @NonNull String familyName,
                                       @NonNull String avatarUrlPath,
                                       @Nullable byte[] profileKey,
                                       boolean noteToSelfArchived,
                                       boolean noteToSelfForcedUnread,
                                       boolean readReceipts,
                                       boolean typingIndicators,
                                       boolean sealedSenderIndicators,
                                       boolean linkPreviewsEnabled,
                                       AccountRecord.PhoneNumberSharingMode phoneNumberSharingMode,
                                       boolean unlistedPhoneNumber,
                                       @NonNull List<PinnedConversation> pinnedConversations,
                                       boolean preferContactAvatars,
                                       GenZappAccountRecord.Payments payments,
                                       int universalExpireTimer,
                                       boolean primarySendsSms,
                                       String e164,
                                       @NonNull List <String> defaultReactions,
                                       @NonNull GenZappAccountRecord.Subscriber subscriber,
                                       boolean displayBadgesOnProfile,
                                       boolean subscriptionManuallyCancelled,
                                       boolean keepMutedChatsArchived,
                                       boolean hasSetMyStoriesPrivacy,
                                       boolean hasViewedOnboardingStory,
                                       boolean hasCompletedUsernameOnboarding,
                                       boolean storiesDisabled,
                                       @NonNull OptionalBool storyViewReceiptsState,
                                       @Nullable String username,
                                       @Nullable AccountRecord.UsernameLink usernameLink)
  {
    return Arrays.equals(contact.serializeUnknownFields(), unknownFields)        &&
           Objects.equals(contact.getGivenName().orElse(""), givenName)          &&
           Objects.equals(contact.getFamilyName().orElse(""), familyName)        &&
           Objects.equals(contact.getAvatarUrlPath().orElse(""), avatarUrlPath)  &&
           Objects.equals(contact.getPayments(), payments)                       &&
           Objects.equals(contact.getE164(), e164)                               &&
           Objects.equals(contact.getDefaultReactions(), defaultReactions)       &&
           Arrays.equals(contact.getProfileKey().orElse(null), profileKey)       &&
           contact.isNoteToSelfArchived() == noteToSelfArchived                  &&
           contact.isNoteToSelfForcedUnread() == noteToSelfForcedUnread          &&
           contact.isReadReceiptsEnabled() == readReceipts                       &&
           contact.isTypingIndicatorsEnabled() == typingIndicators               &&
           contact.isSealedSenderIndicatorsEnabled() == sealedSenderIndicators   &&
           contact.isLinkPreviewsEnabled() == linkPreviewsEnabled                &&
           contact.getPhoneNumberSharingMode() == phoneNumberSharingMode         &&
           contact.isPhoneNumberUnlisted() == unlistedPhoneNumber                &&
           contact.isPreferContactAvatars() == preferContactAvatars              &&
           contact.getUniversalExpireTimer() == universalExpireTimer             &&
           contact.isPrimarySendsSms() == primarySendsSms                        &&
           Objects.equals(contact.getPinnedConversations(), pinnedConversations) &&
           Objects.equals(contact.getSubscriber(), subscriber)                   &&
           contact.isDisplayBadgesOnProfile() == displayBadgesOnProfile          &&
           contact.isSubscriptionManuallyCancelled() == subscriptionManuallyCancelled &&
           contact.isKeepMutedChatsArchived() == keepMutedChatsArchived &&
           contact.hasSetMyStoriesPrivacy() == hasSetMyStoriesPrivacy &&
           contact.hasViewedOnboardingStory() == hasViewedOnboardingStory &&
           contact.hasCompletedUsernameOnboarding() == hasCompletedUsernameOnboarding &&
           contact.isStoriesDisabled() == storiesDisabled &&
           contact.getStoryViewReceiptsState().equals(storyViewReceiptsState) &&
           Objects.equals(contact.getUsername(), username) &&
           Objects.equals(contact.getUsernameLink(), usernameLink);
  }
}
