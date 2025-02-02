package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.database.model.GroupRecord;
import org.thoughtcrime.securesms.groups.GroupChangeBusyException;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.GroupManager;
import org.thoughtcrime.securesms.groups.GroupNotAMemberException;
import org.thoughtcrime.securesms.jobmanager.JsonJobData;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.GenZappservice.api.groupsv2.NoCredentialForRedemptionTimeException;
import org.whispersystems.GenZappservice.api.push.exceptions.PushNetworkException;

import java.io.IOException;
import java.util.Optional;

/**
 * Scheduled by {@link ForceUpdateGroupV2Job} after message queues are drained.
 *
 * Forces a sanity check between local state and server state, and updates local state
 * as necessary.
 */
final class ForceUpdateGroupV2WorkerJob extends BaseJob {

  public static final String KEY = "ForceUpdateGroupV2WorkerJob";

  private static final String TAG = Log.tag(ForceUpdateGroupV2WorkerJob.class);

  private static final String KEY_GROUP_ID = "group_id";

  private final GroupId.V2 groupId;

  ForceUpdateGroupV2WorkerJob(@NonNull GroupId.V2 groupId) {
    this(new Parameters.Builder().setQueue(PushProcessMessageJob.getQueueName(Recipient.externalGroupExact(groupId).getId()))
                                 .addConstraint(NetworkConstraint.KEY)
                                 .setMaxAttempts(Parameters.UNLIMITED)
                                 .build(),
         groupId);
  }

  private ForceUpdateGroupV2WorkerJob(@NonNull Parameters parameters, @NonNull GroupId.V2 groupId) {
    super(parameters);
    this.groupId = groupId;
  }

  @Override
  public @Nullable byte[] serialize() {
    return new JsonJobData.Builder().putString(KEY_GROUP_ID, groupId.toString())
                                    .serialize();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws IOException, GroupNotAMemberException, GroupChangeBusyException {
    Optional<GroupRecord> group = GenZappDatabase.groups().getGroup(groupId);

    if (!group.isPresent()) {
      Log.w(TAG, "Group not found");
      return;
    }

    if (Recipient.externalGroupExact(groupId).isBlocked()) {
      Log.i(TAG, "Not fetching group info for blocked group " + groupId);
      return;
    }

    GroupManager.forceSanityUpdateFromServer(context, group.get().requireV2GroupProperties().getGroupMasterKey(), System.currentTimeMillis());

    GenZappDatabase.groups().setLastForceUpdateTimestamp(group.get().getId(), System.currentTimeMillis());
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception e) {
    return e instanceof PushNetworkException ||
           e instanceof NoCredentialForRedemptionTimeException ||
           e instanceof GroupChangeBusyException;
  }

  @Override
  public void onFailure() {
  }

  public static final class Factory implements Job.Factory<ForceUpdateGroupV2WorkerJob> {

    @Override
    public @NonNull ForceUpdateGroupV2WorkerJob create(@NonNull Parameters parameters, @Nullable byte[] serializedData) {
      JsonJobData data = JsonJobData.deserialize(serializedData);

      return new ForceUpdateGroupV2WorkerJob(parameters,
                                             GroupId.parseOrThrow(data.getString(KEY_GROUP_ID)).requireV2());
    }
  }
}
