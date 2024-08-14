package org.thoughtcrime.securesms.groups.v2;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import org.GenZapp.core.util.logging.Log;
import org.GenZapp.libGenZapp.zkgroup.profiles.ExpiringProfileKeyCredential;
import org.thoughtcrime.securesms.database.RecipientTable;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.ProfileUtil;
import org.whispersystems.GenZappservice.api.GenZappServiceAccountManager;
import org.whispersystems.GenZappservice.api.groupsv2.GroupCandidate;
import org.whispersystems.GenZappservice.api.push.ServiceId;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class GroupCandidateHelper {
  private final GenZappServiceAccountManager GenZappServiceAccountManager;
  private final RecipientTable              recipientTable;

  public GroupCandidateHelper() {
    GenZappServiceAccountManager = AppDependencies.getGenZappServiceAccountManager();
    recipientTable              = GenZappDatabase.recipients();
  }

  private static final String TAG = Log.tag(GroupCandidateHelper.class);

  /**
   * Given a recipient will create a {@link GroupCandidate} which may or may not have a profile key credential.
   * <p>
   * It will try to find missing profile key credentials from the server and persist locally.
   */
  @WorkerThread
  public @NonNull GroupCandidate recipientIdToCandidate(@NonNull RecipientId recipientId)
      throws IOException
  {
    final Recipient recipient = Recipient.resolved(recipientId);

    ServiceId serviceId = recipient.getServiceId().orElse(null);
    if (serviceId == null) {
      throw new AssertionError("Non UUID members should have need detected by now");
    }

    Optional<ExpiringProfileKeyCredential> expiringProfileKeyCredential = Optional.ofNullable(recipient.getExpiringProfileKeyCredential());
    GroupCandidate                         candidate                    = new GroupCandidate(serviceId, expiringProfileKeyCredential);

    if (!candidate.hasValidProfileKeyCredential()) {
      recipientTable.clearProfileKeyCredential(recipient.getId());

      Optional<ExpiringProfileKeyCredential> credential = ProfileUtil.updateExpiringProfileKeyCredential(recipient);
      if (credential.isPresent()) {
        candidate = candidate.withExpiringProfileKeyCredential(credential.get());
      } else {
        candidate = candidate.withoutExpiringProfileKeyCredential();
      }
    }

    return candidate;
  }

  @WorkerThread
  public @NonNull Set<GroupCandidate> recipientIdsToCandidates(@NonNull Collection<RecipientId> recipientIds)
      throws IOException
  {
    Set<GroupCandidate> result = new HashSet<>(recipientIds.size());

    for (RecipientId recipientId : recipientIds) {
      result.add(recipientIdToCandidate(recipientId));
    }

    return result;
  }

  @WorkerThread
  public @NonNull List<GroupCandidate> recipientIdsToCandidatesList(@NonNull Collection<RecipientId> recipientIds)
      throws IOException
  {
    List<GroupCandidate> result = new ArrayList<>(recipientIds.size());

    for (RecipientId recipientId : recipientIds) {
      result.add(recipientIdToCandidate(recipientId));
    }

    return result;
  }
}
