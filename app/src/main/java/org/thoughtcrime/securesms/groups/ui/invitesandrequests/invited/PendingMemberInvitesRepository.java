package org.thoughtcrime.securesms.groups.ui.invitesandrequests.invited;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.core.util.Consumer;

import com.annimon.stream.Stream;

import org.GenZapp.core.util.concurrent.GenZappExecutors;
import org.GenZapp.core.util.logging.Log;
import org.GenZapp.libGenZapp.zkgroup.InvalidInputException;
import org.GenZapp.libGenZapp.zkgroup.groups.UuidCiphertext;
import org.GenZapp.storageservice.protos.groups.local.DecryptedGroup;
import org.GenZapp.storageservice.protos.groups.local.DecryptedPendingMember;
import org.thoughtcrime.securesms.database.GroupTable;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.groups.GroupChangeException;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.GroupManager;
import org.thoughtcrime.securesms.groups.GroupProtoUtil;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.recipients.Recipient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

import okio.ByteString;

/**
 * Repository for modifying the pending members on a single group.
 */
final class PendingMemberInvitesRepository {

  private static final String TAG = Log.tag(PendingMemberInvitesRepository.class);

  private final Context    context;
  private final GroupId.V2 groupId;
  private final Executor   executor;

  PendingMemberInvitesRepository(@NonNull Context context, @NonNull GroupId.V2 groupId) {
    this.context  = context.getApplicationContext();
    this.executor = GenZappExecutors.BOUNDED;
    this.groupId  = groupId;
  }

  public void getInvitees(@NonNull Consumer<InviteeResult> onInviteesLoaded) {
    executor.execute(() -> {
      GroupTable                                   groupDatabase      = GenZappDatabase.groups();
      GroupTable.V2GroupProperties                 v2GroupProperties  = groupDatabase.getGroup(groupId).get().requireV2GroupProperties();
      DecryptedGroup                               decryptedGroup     = v2GroupProperties.getDecryptedGroup();
      List<DecryptedPendingMember>                 pendingMembersList = decryptedGroup.pendingMembers;
      List<SinglePendingMemberInvitedByYou>        byMe               = new ArrayList<>(pendingMembersList.size());
      List<MultiplePendingMembersInvitedByAnother> byOthers           = new ArrayList<>(pendingMembersList.size());
      ByteString                                   self               = GenZappStore.account().requireAci().toByteString();
      boolean                                      selfIsAdmin        = v2GroupProperties.isAdmin(Recipient.self());

      Stream.of(pendingMembersList)
            .groupBy(m -> m.addedByAci)
            .forEach(g ->
              {
                ByteString                   inviterAci     = g.getKey();
                List<DecryptedPendingMember> invitedMembers = g.getValue();

                if (self.equals(inviterAci)) {
                  for (DecryptedPendingMember pendingMember : invitedMembers) {
                    try {
                      Recipient      invitee        = GroupProtoUtil.pendingMemberToRecipient(pendingMember);
                      UuidCiphertext uuidCipherText = new UuidCiphertext(pendingMember.serviceIdCipherText.toByteArray());

                      byMe.add(new SinglePendingMemberInvitedByYou(invitee, uuidCipherText));
                    } catch (InvalidInputException e) {
                      Log.w(TAG, e);
                    }
                  }
                } else {
                  Recipient                 inviter         = GroupProtoUtil.pendingMemberServiceIdToRecipient(inviterAci);
                  ArrayList<UuidCiphertext> uuidCipherTexts = new ArrayList<>(invitedMembers.size());

                  for (DecryptedPendingMember pendingMember : invitedMembers) {
                    try {
                      uuidCipherTexts.add(new UuidCiphertext(pendingMember.serviceIdCipherText.toByteArray()));
                    } catch (InvalidInputException e) {
                      Log.w(TAG, e);
                    }
                  }

                  byOthers.add(new MultiplePendingMembersInvitedByAnother(inviter, uuidCipherTexts));
                }
              }
            );

      onInviteesLoaded.accept(new InviteeResult(byMe, byOthers, selfIsAdmin));
    });
  }

  @WorkerThread
  boolean revokeInvites(@NonNull Collection<UuidCiphertext> uuidCipherTexts) {
    try {
      GroupManager.revokeInvites(context, GenZappStore.account().requireAci(), groupId, uuidCipherTexts);
      return true;
    } catch (GroupChangeException | IOException e) {
      Log.w(TAG, e);
      return false;
    }
  }

  public static final class InviteeResult {
    private final List<SinglePendingMemberInvitedByYou>        byMe;
    private final List<MultiplePendingMembersInvitedByAnother> byOthers;
    private final boolean                                      canRevokeInvites;

    private InviteeResult(List<SinglePendingMemberInvitedByYou> byMe,
                          List<MultiplePendingMembersInvitedByAnother> byOthers,
                          boolean canRevokeInvites)
    {
      this.byMe             = byMe;
      this.byOthers         = byOthers;
      this.canRevokeInvites = canRevokeInvites;
    }

    public List<SinglePendingMemberInvitedByYou> getByMe() {
      return byMe;
    }

    public List<MultiplePendingMembersInvitedByAnother> getByOthers() {
      return byOthers;
    }

    public boolean isCanRevokeInvites() {
      return canRevokeInvites;
    }
  }

  public final static class SinglePendingMemberInvitedByYou {
    private final Recipient      invitee;
    private final UuidCiphertext inviteeCipherText;

    private SinglePendingMemberInvitedByYou(@NonNull Recipient invitee, @NonNull UuidCiphertext inviteeCipherText) {
      this.invitee           = invitee;
      this.inviteeCipherText = inviteeCipherText;
    }

    public Recipient getInvitee() {
      return invitee;
    }

    public UuidCiphertext getInviteeCipherText() {
      return inviteeCipherText;
    }
  }

  public final static class MultiplePendingMembersInvitedByAnother {
    private final Recipient                  inviter;
    private final Collection<UuidCiphertext> uuidCipherTexts;

    private MultiplePendingMembersInvitedByAnother(@NonNull Recipient inviter, @NonNull Collection<UuidCiphertext> uuidCipherTexts) {
      this.inviter         = inviter;
      this.uuidCipherTexts = uuidCipherTexts;
    }

    public Recipient getInviter() {
      return inviter;
    }

    public Collection<UuidCiphertext> getUuidCipherTexts() {
      return uuidCipherTexts;
    }
  }
}