package org.thoughtcrime.securesms.storage;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;

import org.GenZapp.core.util.Base64;
import org.GenZapp.core.util.SetUtil;
import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.components.settings.app.subscription.InAppPaymentsRepository;
import org.thoughtcrime.securesms.database.RecipientTable;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.database.model.InAppPaymentSubscriberRecord;
import org.thoughtcrime.securesms.database.model.RecipientRecord;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobs.RetrieveProfileAvatarJob;
import org.thoughtcrime.securesms.jobs.StorageSyncJob;
import org.thoughtcrime.securesms.keyvalue.AccountValues;
import org.thoughtcrime.securesms.keyvalue.PhoneNumberPrivacyValues.PhoneNumberDiscoverabilityMode;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.payments.Entropy;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.GenZappservice.api.push.UsernameLinkComponents;
import org.whispersystems.GenZappservice.api.storage.GenZappAccountRecord;
import org.whispersystems.GenZappservice.api.storage.GenZappContactRecord;
import org.whispersystems.GenZappservice.api.storage.GenZappStorageManifest;
import org.whispersystems.GenZappservice.api.storage.GenZappStorageRecord;
import org.whispersystems.GenZappservice.api.storage.StorageId;
import org.whispersystems.GenZappservice.api.util.OptionalUtil;
import org.whispersystems.GenZappservice.api.util.UuidUtil;
import org.whispersystems.GenZappservice.internal.storage.protos.AccountRecord;
import org.whispersystems.GenZappservice.internal.storage.protos.OptionalBool;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okio.ByteString;

public final class StorageSyncHelper {

  private static final String TAG = Log.tag(StorageSyncHelper.class);

  public static final StorageKeyGenerator KEY_GENERATOR = () -> Util.getSecretBytes(16);

  private static StorageKeyGenerator keyGenerator = KEY_GENERATOR;

  private static final long REFRESH_INTERVAL = TimeUnit.HOURS.toMillis(2);

  /**
   * Given a list of all the local and remote keys you know about, this will return a result telling
   * you which keys are exclusively remote and which are exclusively local.
   *
   * @param remoteIds All remote keys available.
   * @param localIds  All local keys available.
   * @return An object describing which keys are exclusive to the remote data set and which keys are
   * exclusive to the local data set.
   */
  public static @NonNull IdDifferenceResult findIdDifference(@NonNull Collection<StorageId> remoteIds,
                                                             @NonNull Collection<StorageId> localIds)
  {
    Map<String, StorageId> remoteByRawId = Stream.of(remoteIds).collect(Collectors.toMap(id -> Base64.encodeWithPadding(id.getRaw()), id -> id));
    Map<String, StorageId> localByRawId  = Stream.of(localIds).collect(Collectors.toMap(id -> Base64.encodeWithPadding(id.getRaw()), id -> id));

    boolean hasTypeMismatch = remoteByRawId.size() != remoteIds.size() || localByRawId.size() != localIds.size();

    Set<String> remoteOnlyRawIds = SetUtil.difference(remoteByRawId.keySet(), localByRawId.keySet());
    Set<String> localOnlyRawIds  = SetUtil.difference(localByRawId.keySet(), remoteByRawId.keySet());
    Set<String> sharedRawIds     = SetUtil.intersection(localByRawId.keySet(), remoteByRawId.keySet());

    for (String rawId : sharedRawIds) {
      StorageId remote = Objects.requireNonNull(remoteByRawId.get(rawId));
      StorageId local  = Objects.requireNonNull(localByRawId.get(rawId));

      if (remote.getType() != local.getType()) {
        remoteOnlyRawIds.remove(rawId);
        localOnlyRawIds.remove(rawId);
        hasTypeMismatch = true;
        Log.w(TAG, "Remote type " + remote.getType() + " did not match local type " + local.getType() + "!");
      }
    }

    List<StorageId> remoteOnlyKeys = Stream.of(remoteOnlyRawIds).map(remoteByRawId::get).toList();
    List<StorageId> localOnlyKeys  = Stream.of(localOnlyRawIds).map(localByRawId::get).toList();

    return new IdDifferenceResult(remoteOnlyKeys, localOnlyKeys, hasTypeMismatch);
  }

  public static @NonNull byte[] generateKey() {
    return keyGenerator.generate();
  }

  @VisibleForTesting
  static void setTestKeyGenerator(@Nullable StorageKeyGenerator testKeyGenerator) {
    keyGenerator = testKeyGenerator != null ? testKeyGenerator : KEY_GENERATOR;
  }

  public static boolean profileKeyChanged(StorageRecordUpdate<GenZappContactRecord> update) {
    return !OptionalUtil.byteArrayEquals(update.getOld().getProfileKey(), update.getNew().getProfileKey());
  }

  public static GenZappStorageRecord buildAccountRecord(@NonNull Context context, @NonNull Recipient self) {
    RecipientTable        recipientTable = GenZappDatabase.recipients();
    RecipientRecord       record         = recipientTable.getRecordForSync(self.getId());
    List<RecipientRecord> pinned         = Stream.of(GenZappDatabase.threads().getPinnedRecipientIds())
                                                 .map(recipientTable::getRecordForSync)
                                                 .toList();

    final OptionalBool storyViewReceiptsState = GenZappStore.story().getViewedReceiptsEnabled() ? OptionalBool.ENABLED
                                                                                               : OptionalBool.DISABLED;

    if (self.getStorageId() == null || (record != null && record.getStorageId() == null)) {
      Log.w(TAG, "[buildAccountRecord] No storageId for self or record! Generating. (Self: " + (self.getStorageId() != null) + ", Record: " + (record != null && record.getStorageId() != null) + ")");
      GenZappDatabase.recipients().updateStorageId(self.getId(), generateKey());
      self   = Recipient.self().fresh();
      record = recipientTable.getRecordForSync(self.getId());
    }

    if (record == null) {
      Log.w(TAG, "[buildAccountRecord] Could not find a RecipientRecord for ourselves! ID: " + self.getId());
    } else if (!Arrays.equals(record.getStorageId(), self.getStorageId())) {
      Log.w(TAG, "[buildAccountRecord] StorageId on RecipientRecord did not match self! ID: " + self.getId());
    }

    byte[] storageId = record != null && record.getStorageId() != null ? record.getStorageId() : self.getStorageId();

    GenZappAccountRecord.Builder account = new GenZappAccountRecord.Builder(storageId, record != null ? record.getSyncExtras().getStorageProto() : null)
                                                                 .setProfileKey(self.getProfileKey())
                                                                 .setGivenName(self.getProfileName().getGivenName())
                                                                 .setFamilyName(self.getProfileName().getFamilyName())
                                                                 .setAvatarUrlPath(self.getProfileAvatar())
                                                                 .setNoteToSelfArchived(record != null && record.getSyncExtras().isArchived())
                                                                 .setNoteToSelfForcedUnread(record != null && record.getSyncExtras().isForcedUnread())
                                                                 .setTypingIndicatorsEnabled(TextSecurePreferences.isTypingIndicatorsEnabled(context))
                                                                 .setReadReceiptsEnabled(TextSecurePreferences.isReadReceiptsEnabled(context))
                                                                 .setSealedSenderIndicatorsEnabled(TextSecurePreferences.isShowUnidentifiedDeliveryIndicatorsEnabled(context))
                                                                 .setLinkPreviewsEnabled(GenZappStore.settings().isLinkPreviewsEnabled())
                                                                 .setUnlistedPhoneNumber(GenZappStore.phoneNumberPrivacy().getPhoneNumberDiscoverabilityMode() == PhoneNumberDiscoverabilityMode.NOT_DISCOVERABLE)
                                                                 .setPhoneNumberSharingMode(StorageSyncModels.localToRemotePhoneNumberSharingMode(GenZappStore.phoneNumberPrivacy().getPhoneNumberSharingMode()))
                                                                 .setPinnedConversations(StorageSyncModels.localToRemotePinnedConversations(pinned))
                                                                 .setPreferContactAvatars(GenZappStore.settings().isPreferSystemContactPhotos())
                                                                 .setPayments(GenZappStore.payments().mobileCoinPaymentsEnabled(), Optional.ofNullable(GenZappStore.payments().getPaymentsEntropy()).map(Entropy::getBytes).orElse(null))
                                                                 .setPrimarySendsSms(false)
                                                                 .setUniversalExpireTimer(GenZappStore.settings().getUniversalExpireTimer())
                                                                 .setDefaultReactions(GenZappStore.emoji().getReactions())
                                                                 .setSubscriber(StorageSyncModels.localToRemoteSubscriber(InAppPaymentsRepository.getSubscriber(InAppPaymentSubscriberRecord.Type.DONATION)))
                                                                 .setDisplayBadgesOnProfile(GenZappStore.inAppPayments().getDisplayBadgesOnProfile())
                                                                 .setSubscriptionManuallyCancelled(InAppPaymentsRepository.isUserManuallyCancelled(InAppPaymentSubscriberRecord.Type.DONATION))
                                                                 .setKeepMutedChatsArchived(GenZappStore.settings().shouldKeepMutedChatsArchived())
                                                                 .setHasSetMyStoriesPrivacy(GenZappStore.story().getUserHasBeenNotifiedAboutStories())
                                                                 .setHasViewedOnboardingStory(GenZappStore.story().getUserHasViewedOnboardingStory())
                                                                 .setStoriesDisabled(GenZappStore.story().isFeatureDisabled())
                                                                 .setStoryViewReceiptsState(storyViewReceiptsState)
                                                                 .setHasSeenGroupStoryEducationSheet(GenZappStore.story().getUserHasSeenGroupStoryEducationSheet())
                                                                 .setUsername(GenZappStore.account().getUsername())
                                                                 .setHasCompletedUsernameOnboarding(GenZappStore.uiHints().hasCompletedUsernameOnboarding());

    UsernameLinkComponents linkComponents = GenZappStore.account().getUsernameLink();
    if (linkComponents != null) {
      account.setUsernameLink(new AccountRecord.UsernameLink.Builder()
                                                            .entropy(ByteString.of(linkComponents.getEntropy()))
                                                            .serverId(UuidUtil.toByteString(linkComponents.getServerId()))
                                                            .color(StorageSyncModels.localToRemoteUsernameColor(GenZappStore.misc().getUsernameQrCodeColorScheme()))
                                                            .build());
    } else {
      account.setUsernameLink(null);
    }

    return GenZappStorageRecord.forAccount(account.build());
  }

  public static void applyAccountStorageSyncUpdates(@NonNull Context context, @NonNull Recipient self, @NonNull GenZappAccountRecord updatedRecord, boolean fetchProfile) {
    GenZappAccountRecord localRecord = buildAccountRecord(context, self).getAccount().get();
    applyAccountStorageSyncUpdates(context, self, new StorageRecordUpdate<>(localRecord, updatedRecord), fetchProfile);
  }

  public static void applyAccountStorageSyncUpdates(@NonNull Context context, @NonNull Recipient self, @NonNull StorageRecordUpdate<GenZappAccountRecord> update, boolean fetchProfile) {
    GenZappDatabase.recipients().applyStorageSyncAccountUpdate(update);

    TextSecurePreferences.setReadReceiptsEnabled(context, update.getNew().isReadReceiptsEnabled());
    TextSecurePreferences.setTypingIndicatorsEnabled(context, update.getNew().isTypingIndicatorsEnabled());
    TextSecurePreferences.setShowUnidentifiedDeliveryIndicatorsEnabled(context, update.getNew().isSealedSenderIndicatorsEnabled());
    GenZappStore.settings().setLinkPreviewsEnabled(update.getNew().isLinkPreviewsEnabled());
    GenZappStore.phoneNumberPrivacy().setPhoneNumberDiscoverabilityMode(update.getNew().isPhoneNumberUnlisted() ? PhoneNumberDiscoverabilityMode.NOT_DISCOVERABLE : PhoneNumberDiscoverabilityMode.DISCOVERABLE);
    GenZappStore.phoneNumberPrivacy().setPhoneNumberSharingMode(StorageSyncModels.remoteToLocalPhoneNumberSharingMode(update.getNew().getPhoneNumberSharingMode()));
    GenZappStore.settings().setPreferSystemContactPhotos(update.getNew().isPreferContactAvatars());
    GenZappStore.payments().setEnabledAndEntropy(update.getNew().getPayments().isEnabled(), Entropy.fromBytes(update.getNew().getPayments().getEntropy().orElse(null)));
    GenZappStore.settings().setUniversalExpireTimer(update.getNew().getUniversalExpireTimer());
    GenZappStore.emoji().setReactions(update.getNew().getDefaultReactions());
    GenZappStore.inAppPayments().setDisplayBadgesOnProfile(update.getNew().isDisplayBadgesOnProfile());
    GenZappStore.settings().setKeepMutedChatsArchived(update.getNew().isKeepMutedChatsArchived());
    GenZappStore.story().setUserHasBeenNotifiedAboutStories(update.getNew().hasSetMyStoriesPrivacy());
    GenZappStore.story().setUserHasViewedOnboardingStory(update.getNew().hasViewedOnboardingStory());
    GenZappStore.story().setFeatureDisabled(update.getNew().isStoriesDisabled());
    GenZappStore.story().setUserHasSeenGroupStoryEducationSheet(update.getNew().hasSeenGroupStoryEducationSheet());
    GenZappStore.uiHints().setHasCompletedUsernameOnboarding(update.getNew().hasCompletedUsernameOnboarding());

    if (update.getNew().getStoryViewReceiptsState() == OptionalBool.UNSET) {
      GenZappStore.story().setViewedReceiptsEnabled(update.getNew().isReadReceiptsEnabled());
    } else {
      GenZappStore.story().setViewedReceiptsEnabled(update.getNew().getStoryViewReceiptsState() == OptionalBool.ENABLED);
    }

    if (update.getNew().getStoryViewReceiptsState() == OptionalBool.UNSET) {
      GenZappStore.story().setViewedReceiptsEnabled(update.getNew().isReadReceiptsEnabled());
    } else {
      GenZappStore.story().setViewedReceiptsEnabled(update.getNew().getStoryViewReceiptsState() == OptionalBool.ENABLED);
    }

    InAppPaymentSubscriberRecord remoteSubscriber = StorageSyncModels.remoteToLocalSubscriber(update.getNew().getSubscriber(), InAppPaymentSubscriberRecord.Type.DONATION);
    if (remoteSubscriber != null) {
      InAppPaymentsRepository.setSubscriber(remoteSubscriber);
    }

    if (update.getNew().isSubscriptionManuallyCancelled() && !update.getOld().isSubscriptionManuallyCancelled()) {
      GenZappStore.inAppPayments().updateLocalStateForManualCancellation(InAppPaymentSubscriberRecord.Type.DONATION);
    }

    if (fetchProfile && update.getNew().getAvatarUrlPath().isPresent()) {
      AppDependencies.getJobManager().add(new RetrieveProfileAvatarJob(self, update.getNew().getAvatarUrlPath().get()));
    }

    if (!update.getNew().getUsername().equals(update.getOld().getUsername())) {
      GenZappStore.account().setUsername(update.getNew().getUsername());
      GenZappStore.account().setUsernameSyncState(AccountValues.UsernameSyncState.IN_SYNC);
      GenZappStore.account().setUsernameSyncErrorCount(0);
    }

    if (update.getNew().getUsernameLink() != null) {
      GenZappStore.account().setUsernameLink(
          new UsernameLinkComponents(
              update.getNew().getUsernameLink().entropy.toByteArray(),
              UuidUtil.parseOrThrow(update.getNew().getUsernameLink().serverId.toByteArray())
          )
      );
      GenZappStore.misc().setUsernameQrCodeColorScheme(StorageSyncModels.remoteToLocalUsernameColor(update.getNew().getUsernameLink().color));
    }
  }

  public static void scheduleSyncForDataChange() {
    if (!GenZappStore.registration().isRegistrationComplete()) {
      Log.d(TAG, "Registration still ongoing. Ignore sync request.");
      return;
    }
    AppDependencies.getJobManager().add(new StorageSyncJob());
  }

  public static void scheduleRoutineSync() {
    long timeSinceLastSync = System.currentTimeMillis() - GenZappStore.storageService().getLastSyncTime();

    if (timeSinceLastSync > REFRESH_INTERVAL) {
      Log.d(TAG, "Scheduling a sync. Last sync was " + timeSinceLastSync + " ms ago.");
      scheduleSyncForDataChange();
    } else {
      Log.d(TAG, "No need for sync. Last sync was " + timeSinceLastSync + " ms ago.");
    }
  }

  public static final class IdDifferenceResult {
    private final List<StorageId> remoteOnlyIds;
    private final List<StorageId> localOnlyIds;
    private final boolean         hasTypeMismatches;

    private IdDifferenceResult(@NonNull List<StorageId> remoteOnlyIds,
                               @NonNull List<StorageId> localOnlyIds,
                               boolean hasTypeMismatches)
    {
      this.remoteOnlyIds     = remoteOnlyIds;
      this.localOnlyIds      = localOnlyIds;
      this.hasTypeMismatches = hasTypeMismatches;
    }

    public @NonNull List<StorageId> getRemoteOnlyIds() {
      return remoteOnlyIds;
    }

    public @NonNull List<StorageId> getLocalOnlyIds() {
      return localOnlyIds;
    }

    /**
     * @return True if there exist some keys that have matching raw ID's but different types,
     * otherwise false.
     */
    public boolean hasTypeMismatches() {
      return hasTypeMismatches;
    }

    public boolean isEmpty() {
      return remoteOnlyIds.isEmpty() && localOnlyIds.isEmpty();
    }

    @Override
    public @NonNull String toString() {
      return "remoteOnly: " + remoteOnlyIds.size() + ", localOnly: " + localOnlyIds.size() + ", hasTypeMismatches: " + hasTypeMismatches;
    }
  }

  public static final class WriteOperationResult {
    private final GenZappStorageManifest     manifest;
    private final List<GenZappStorageRecord> inserts;
    private final List<byte[]>              deletes;

    public WriteOperationResult(@NonNull GenZappStorageManifest manifest,
                                @NonNull List<GenZappStorageRecord> inserts,
                                @NonNull List<byte[]> deletes)
    {
      this.manifest = manifest;
      this.inserts  = inserts;
      this.deletes  = deletes;
    }

    public @NonNull GenZappStorageManifest getManifest() {
      return manifest;
    }

    public @NonNull List<GenZappStorageRecord> getInserts() {
      return inserts;
    }

    public @NonNull List<byte[]> getDeletes() {
      return deletes;
    }

    public boolean isEmpty() {
      return inserts.isEmpty() && deletes.isEmpty();
    }

    @Override
    public @NonNull String toString() {
      if (isEmpty()) {
        return "Empty";
      } else {
        return String.format(Locale.ENGLISH,
                             "ManifestVersion: %d, Total Keys: %d, Inserts: %d, Deletes: %d",
                             manifest.getVersion(),
                             manifest.getStorageIds().size(),
                             inserts.size(),
                             deletes.size());
      }
    }
  }
}
