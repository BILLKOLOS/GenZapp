package org.thoughtcrime.securesms.groups.ui.creategroup.details;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;

import org.GenZapp.core.util.concurrent.GenZappExecutors;
import org.thoughtcrime.securesms.groups.GroupChangeBusyException;
import org.thoughtcrime.securesms.groups.GroupChangeException;
import org.thoughtcrime.securesms.groups.GroupManager;
import org.thoughtcrime.securesms.groups.ui.GroupMemberEntry;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

final class AddGroupDetailsRepository {

  private final Context context;

  AddGroupDetailsRepository(@NonNull Context context) {
    this.context = context;
  }

  void resolveMembers(@NonNull Collection<RecipientId> recipientIds, Consumer<List<GroupMemberEntry.NewGroupCandidate>> consumer) {
    GenZappExecutors.BOUNDED.execute(() -> {
      List<GroupMemberEntry.NewGroupCandidate> members = new ArrayList<>(recipientIds.size());

      for (RecipientId id : recipientIds) {
        members.add(new GroupMemberEntry.NewGroupCandidate(Recipient.resolved(id)));
      }

      consumer.accept(members);
    });
  }

  void createGroup(@NonNull Set<RecipientId> members,
                   @Nullable byte[] avatar,
                   @Nullable String name,
                   @Nullable Integer disappearingMessagesTimer,
                   Consumer<GroupCreateResult> resultConsumer)
  {
    GenZappExecutors.BOUNDED.execute(() -> {
      try {
        GroupManager.GroupActionResult result = GroupManager.createGroup(context,
                                                                         members,
                                                                         avatar,
                                                                         name,
                                                                         disappearingMessagesTimer != null ? disappearingMessagesTimer
                                                                                                           : GenZappStore.settings().getUniversalExpireTimer());

        resultConsumer.accept(GroupCreateResult.success(result));
      } catch (GroupChangeBusyException e) {
        resultConsumer.accept(GroupCreateResult.error(GroupCreateResult.Error.Type.ERROR_BUSY));
      } catch (GroupChangeException e) {
        resultConsumer.accept(GroupCreateResult.error(GroupCreateResult.Error.Type.ERROR_FAILED));
      } catch (IOException e) {
        resultConsumer.accept(GroupCreateResult.error(GroupCreateResult.Error.Type.ERROR_IO));
      }
    });
  }
}
