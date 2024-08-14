package org.thoughtcrime.securesms.storage;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.GenZapp.core.util.logging.Log;
import org.GenZapp.libGenZapp.zkgroup.groups.GroupMasterKey;
import org.thoughtcrime.securesms.database.GroupTable;
import org.thoughtcrime.securesms.database.RecipientTable;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.whispersystems.GenZappservice.api.storage.GenZappGroupV2Record;
import org.whispersystems.GenZappservice.internal.storage.protos.GroupV2Record;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

public final class GroupV2RecordProcessor extends DefaultStorageRecordProcessor<GenZappGroupV2Record> {

  private static final String TAG = Log.tag(GroupV2RecordProcessor.class);

  private final Context        context;
  private final RecipientTable recipientTable;
  private final GroupTable     groupDatabase;
  private final Map<GroupId.V2, GroupId.V1> gv1GroupsByExpectedGv2Id;

  public GroupV2RecordProcessor(@NonNull Context context) {
    this(context, GenZappDatabase.recipients(), GenZappDatabase.groups());
  }

  GroupV2RecordProcessor(@NonNull Context context, @NonNull RecipientTable recipientTable, @NonNull GroupTable groupDatabase) {
    this.context                  = context;
    this.recipientTable           = recipientTable;
    this.groupDatabase            = groupDatabase;
    this.gv1GroupsByExpectedGv2Id = groupDatabase.getAllExpectedV2Ids();
  }

  @Override
  boolean isInvalid(@NonNull GenZappGroupV2Record remote) {
    return remote.getMasterKeyBytes().length != GroupMasterKey.SIZE;
  }

  @Override
  @NonNull Optional<GenZappGroupV2Record> getMatching(@NonNull GenZappGroupV2Record record, @NonNull StorageKeyGenerator keyGenerator) {
    GroupId.V2 groupId = GroupId.v2(record.getMasterKeyOrThrow());

    Optional<RecipientId> recipientId = recipientTable.getByGroupId(groupId);

    return recipientId.map(recipientTable::getRecordForSync)
                      .map(settings -> {
                        if (settings.getSyncExtras().getGroupMasterKey() != null) {
                          return StorageSyncModels.localToRemoteRecord(settings);
                        } else {
                          Log.w(TAG, "No local master key. Assuming it matches remote since the groupIds match. Enqueuing a fetch to fix the bad state.");
                          groupDatabase.fixMissingMasterKey(record.getMasterKeyOrThrow());
                          return StorageSyncModels.localToRemoteRecord(settings, record.getMasterKeyOrThrow());
                        }
                      })
                      .map(r -> r.getGroupV2().get());
  }

  @Override
  @NonNull GenZappGroupV2Record merge(@NonNull GenZappGroupV2Record remote, @NonNull GenZappGroupV2Record local, @NonNull StorageKeyGenerator keyGenerator) {
    byte[]                      unknownFields              = remote.serializeUnknownFields();
    boolean                     blocked                    = remote.isBlocked();
    boolean                     profileSharing             = remote.isProfileSharingEnabled();
    boolean                     archived                   = remote.isArchived();
    boolean                     forcedUnread               = remote.isForcedUnread();
    long                        muteUntil                  = remote.getMuteUntil();
    boolean                     notifyForMentionsWhenMuted = remote.notifyForMentionsWhenMuted();
    boolean                     hideStory                  = remote.shouldHideStory();
    GroupV2Record.StorySendMode storySendMode              = remote.getStorySendMode();

    boolean matchesRemote = doParamsMatch(remote, unknownFields, blocked, profileSharing, archived, forcedUnread, muteUntil, notifyForMentionsWhenMuted, hideStory, storySendMode);
    boolean matchesLocal  = doParamsMatch(local, unknownFields, blocked, profileSharing, archived, forcedUnread, muteUntil, notifyForMentionsWhenMuted, hideStory, storySendMode);

    if (matchesRemote) {
      return remote;
    } else if (matchesLocal) {
      return local;
    } else {
      return new GenZappGroupV2Record.Builder(keyGenerator.generate(), remote.getMasterKeyBytes(), unknownFields)
                                    .setBlocked(blocked)
                                    .setProfileSharingEnabled(profileSharing)
                                    .setArchived(archived)
                                    .setForcedUnread(forcedUnread)
                                    .setMuteUntil(muteUntil)
                                    .setNotifyForMentionsWhenMuted(notifyForMentionsWhenMuted)
                                    .setHideStory(hideStory)
                                    .setStorySendMode(storySendMode)
                                    .build();
    }
  }

  @Override
  void insertLocal(@NonNull GenZappGroupV2Record record) {
    recipientTable.applyStorageSyncGroupV2Insert(record);
  }

  @Override
  void updateLocal(@NonNull StorageRecordUpdate<GenZappGroupV2Record> update) {
    recipientTable.applyStorageSyncGroupV2Update(update);
  }

  @Override
  public int compare(@NonNull GenZappGroupV2Record lhs, @NonNull GenZappGroupV2Record rhs) {
    if (Arrays.equals(lhs.getMasterKeyBytes(), rhs.getMasterKeyBytes())) {
      return 0;
    } else {
      return 1;
    }
  }

  private boolean doParamsMatch(@NonNull GenZappGroupV2Record group,
                                @Nullable byte[] unknownFields,
                                boolean blocked,
                                boolean profileSharing,
                                boolean archived,
                                boolean forcedUnread,
                                long muteUntil,
                                boolean notifyForMentionsWhenMuted,
                                boolean hideStory,
                                @NonNull GroupV2Record.StorySendMode storySendMode)
  {
    return Arrays.equals(unknownFields, group.serializeUnknownFields())     &&
           blocked == group.isBlocked()                                     &&
           profileSharing == group.isProfileSharingEnabled()                &&
           archived == group.isArchived()                                   &&
           forcedUnread == group.isForcedUnread()                           &&
           muteUntil == group.getMuteUntil()                                &&
           notifyForMentionsWhenMuted == group.notifyForMentionsWhenMuted() &&
           hideStory == group.shouldHideStory()                             &&
           storySendMode == group.getStorySendMode();
  }
}
