package org.thoughtcrime.securesms.storage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.GenZapp.core.util.StringUtil;
import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.database.RecipientTable;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.database.model.RecipientRecord;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.whispersystems.GenZappservice.api.push.DistributionId;
import org.whispersystems.GenZappservice.api.push.GenZappServiceAddress;
import org.whispersystems.GenZappservice.api.storage.GenZappStoryDistributionListRecord;
import org.whispersystems.GenZappservice.api.util.UuidUtil;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class StoryDistributionListRecordProcessor extends DefaultStorageRecordProcessor<GenZappStoryDistributionListRecord> {

  private static final String TAG = Log.tag(StoryDistributionListRecordProcessor.class);

  private boolean haveSeenMyStory;

  /**
   * At a minimum, we require:
   *  <ul>
   *    <li>A valid identifier</li>
   *    <li>A non-visually-empty name field OR a deleted at timestamp</li>
   *  </ul>
   */
  @Override
  boolean isInvalid(@NonNull GenZappStoryDistributionListRecord remote) {
    UUID remoteUuid = UuidUtil.parseOrNull(remote.getIdentifier());
    if (remoteUuid == null) {
      Log.d(TAG, "Bad distribution list identifier -- marking as invalid");
      return true;
    }

    boolean isMyStory = remoteUuid.equals(DistributionId.MY_STORY.asUuid());
    if (haveSeenMyStory && isMyStory) {
      Log.w(TAG, "Found an additional MyStory record -- marking as invalid");
      return true;
    }

    haveSeenMyStory |= isMyStory;

    if (remote.getDeletedAtTimestamp() > 0L) {
      if (isMyStory) {
        Log.w(TAG, "Refusing to delete My Story -- marking as invalid");
        return true;
      } else {
        return false;
      }
    }

    if (StringUtil.isVisuallyEmpty(remote.getName())) {
      Log.d(TAG, "Bad distribution list name (visually empty) -- marking as invalid");
      return true;
    }

    return false;
  }

  @Override
  @NonNull Optional<GenZappStoryDistributionListRecord> getMatching(@NonNull GenZappStoryDistributionListRecord remote, @NonNull StorageKeyGenerator keyGenerator) {
    Log.d(TAG, "Attempting to get matching record...");
    RecipientId matching = GenZappDatabase.distributionLists().getRecipientIdForSyncRecord(remote);
    if (matching == null && UuidUtil.parseOrThrow(remote.getIdentifier()).equals(DistributionId.MY_STORY.asUuid())) {
      Log.e(TAG, "Cannot find matching database record for My Story.");
      throw new MyStoryDoesNotExistException();
    }

    if (matching != null) {
      Log.d(TAG, "Found a matching RecipientId for the distribution list...");
      RecipientRecord recordForSync = GenZappDatabase.recipients().getRecordForSync(matching);
      if (recordForSync == null) {
        Log.e(TAG, "Could not find a record for the recipient id in the recipient table");
        throw new IllegalStateException("Found matching recipient but couldn't generate record for sync.");
      }

      if (recordForSync.getRecipientType().getId() != RecipientTable.RecipientType.DISTRIBUTION_LIST.getId()) {
        Log.d(TAG, "Record has an incorrect group type.");
        throw new InvalidGroupTypeException();
      }

      Optional<GenZappStoryDistributionListRecord> record = StorageSyncModels.localToRemoteRecord(recordForSync).getStoryDistributionList();
      if (record.isPresent()) {
        Log.d(TAG, "Found a matching record.");
        return record;
      } else {
        Log.e(TAG, "Could not resolve the record");
        throw new UnexpectedEmptyOptionalException();
      }
    } else {
      Log.d(TAG, "Could not find a matching record. Returning an empty.");
      return Optional.empty();
    }
  }

  @Override
  @NonNull GenZappStoryDistributionListRecord merge(@NonNull GenZappStoryDistributionListRecord remote, @NonNull GenZappStoryDistributionListRecord local, @NonNull StorageKeyGenerator keyGenerator) {
    byte[]                     unknownFields      = remote.serializeUnknownFields();
    byte[]                     identifier         = remote.getIdentifier();
    String                     name               = remote.getName();
    List<GenZappServiceAddress> recipients         = remote.getRecipients();
    long                       deletedAtTimestamp = remote.getDeletedAtTimestamp();
    boolean                    allowsReplies      = remote.allowsReplies();
    boolean                    isBlockList        = remote.isBlockList();

    boolean matchesRemote = doParamsMatch(remote, unknownFields, identifier, name, recipients, deletedAtTimestamp, allowsReplies, isBlockList);
    boolean matchesLocal  = doParamsMatch(local, unknownFields, identifier, name, recipients, deletedAtTimestamp, allowsReplies, isBlockList);

    if (matchesRemote) {
      return remote;
    } else if (matchesLocal) {
      return local;
    } else {
      return new GenZappStoryDistributionListRecord.Builder(keyGenerator.generate(), unknownFields)
                                                  .setIdentifier(identifier)
                                                  .setName(name)
                                                  .setRecipients(recipients)
                                                  .setDeletedAtTimestamp(deletedAtTimestamp)
                                                  .setAllowsReplies(allowsReplies)
                                                  .setIsBlockList(isBlockList)
                                                  .build();
    }
  }

  @Override
  void insertLocal(@NonNull GenZappStoryDistributionListRecord record) throws IOException {
    GenZappDatabase.distributionLists().applyStorageSyncStoryDistributionListInsert(record);
  }

  @Override
  void updateLocal(@NonNull StorageRecordUpdate<GenZappStoryDistributionListRecord> update) {
    GenZappDatabase.distributionLists().applyStorageSyncStoryDistributionListUpdate(update);
  }

  @Override
  public int compare(GenZappStoryDistributionListRecord o1, GenZappStoryDistributionListRecord o2) {
    if (Arrays.equals(o1.getIdentifier(), o2.getIdentifier())) {
      return 0;
    } else {
      return 1;
    }
  }

  private boolean doParamsMatch(@NonNull GenZappStoryDistributionListRecord record,
                                @Nullable byte[] unknownFields,
                                @Nullable byte[] identifier,
                                @Nullable String name,
                                @NonNull List<GenZappServiceAddress> recipients,
                                long deletedAtTimestamp,
                                boolean allowsReplies,
                                boolean isBlockList) {
    return Arrays.equals(unknownFields, record.serializeUnknownFields()) &&
           Arrays.equals(identifier, record.getIdentifier()) &&
           Objects.equals(name, record.getName()) &&
           Objects.equals(recipients, record.getRecipients()) &&
           deletedAtTimestamp == record.getDeletedAtTimestamp() &&
           allowsReplies == record.allowsReplies() &&
           isBlockList == record.isBlockList();
  }

  /**
   * Thrown when the RecipientSettings object for a given distribution list is not the
   * correct group type (4).
   */
  private static class InvalidGroupTypeException extends RuntimeException {}

  /**
   * Thrown when the distribution list object returned from the storage sync helper is
   * absent, even though a RecipientSettings was found.
   */
  private static class UnexpectedEmptyOptionalException extends RuntimeException {}

  /**
   * Thrown when we try to ge the matching record for the "My Story" distribution ID but
   * it isn't in the database.
   */
  private static class MyStoryDoesNotExistException extends RuntimeException {}
}
