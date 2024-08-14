package org.thoughtcrime.securesms.components.settings.conversation

import android.content.Context
import android.database.Cursor
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.GenZapp.core.util.concurrent.GenZappExecutors
import org.GenZapp.core.util.logging.Log
import org.GenZapp.storageservice.protos.groups.local.DecryptedGroup
import org.thoughtcrime.securesms.contacts.sync.ContactDiscovery
import org.thoughtcrime.securesms.database.CallTable
import org.thoughtcrime.securesms.database.MediaTable
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.database.model.GroupRecord
import org.thoughtcrime.securesms.database.model.IdentityRecord
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.StoryViewState
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.groups.GroupProtoUtil
import org.thoughtcrime.securesms.groups.LiveGroup
import org.thoughtcrime.securesms.groups.v2.GroupAddMembersResult
import org.thoughtcrime.securesms.groups.v2.GroupManagementRepository
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.recipients.RecipientUtil
import org.thoughtcrime.securesms.util.RemoteConfig
import java.io.IOException

private val TAG = Log.tag(ConversationSettingsRepository::class.java)

class ConversationSettingsRepository(
  private val context: Context,
  private val groupManagementRepository: GroupManagementRepository = GroupManagementRepository(context)
) {

  fun getCallEvents(callRowIds: LongArray): Single<List<Pair<CallTable.Call, MessageRecord>>> {
    return if (callRowIds.isEmpty()) {
      Single.just(emptyList())
    } else {
      Single.fromCallable {
        val callMap = GenZappDatabase.calls.getCallsByRowIds(callRowIds.toList())
        val messageIds = callMap.values.mapNotNull { it.messageId }
        GenZappDatabase.messages.getMessages(messageIds).iterator().asSequence()
          .filter { callMap.containsKey(it.id) }
          .map { callMap[it.id]!! to it }
          .sortedByDescending { it.first.timestamp }
          .toList()
      }
    }
  }

  @WorkerThread
  fun getThreadMedia(threadId: Long, limit: Int): Cursor? {
    return if (threadId > 0) {
      GenZappDatabase.media.getGalleryMediaForThread(threadId, MediaTable.Sorting.Newest, limit)
    } else {
      null
    }
  }

  fun getStoryViewState(groupId: GroupId): Observable<StoryViewState> {
    return Observable.fromCallable {
      GenZappDatabase.recipients.getByGroupId(groupId)
    }.flatMap {
      StoryViewState.getForRecipientId(it.get())
    }.observeOn(Schedulers.io())
  }

  fun getThreadId(recipientId: RecipientId, consumer: (Long) -> Unit) {
    GenZappExecutors.BOUNDED.execute {
      consumer(GenZappDatabase.threads.getThreadIdIfExistsFor(recipientId))
    }
  }

  fun getThreadId(groupId: GroupId, consumer: (Long) -> Unit) {
    GenZappExecutors.BOUNDED.execute {
      val recipientId = Recipient.externalGroupExact(groupId).id
      consumer(GenZappDatabase.threads.getThreadIdIfExistsFor(recipientId))
    }
  }

  fun isInternalRecipientDetailsEnabled(): Boolean = GenZappStore.internal.recipientDetails()

  fun hasGroups(consumer: (Boolean) -> Unit) {
    GenZappExecutors.BOUNDED.execute { consumer(GenZappDatabase.groups.getActiveGroupCount() > 0) }
  }

  fun getIdentity(recipientId: RecipientId, consumer: (IdentityRecord?) -> Unit) {
    GenZappExecutors.BOUNDED.execute {
      if (GenZappStore.account.aci != null && GenZappStore.account.pni != null) {
        consumer(AppDependencies.protocolStore.aci().identities().getIdentityRecord(recipientId).orElse(null))
      } else {
        consumer(null)
      }
    }
  }

  fun getGroupsInCommon(recipientId: RecipientId, consumer: (List<Recipient>) -> Unit) {
    GenZappExecutors.BOUNDED.execute {
      consumer(
        GenZappDatabase
          .groups
          .getPushGroupsContainingMember(recipientId)
          .asSequence()
          .filter { it.members.contains(Recipient.self().id) }
          .map(GroupRecord::recipientId)
          .map(Recipient::resolved)
          .sortedBy { gr -> gr.getDisplayName(context) }
          .toList()
      )
    }
  }

  fun getGroupMembership(recipientId: RecipientId, consumer: (List<RecipientId>) -> Unit) {
    GenZappExecutors.BOUNDED.execute {
      val groupDatabase = GenZappDatabase.groups
      val groupRecords = groupDatabase.getPushGroupsContainingMember(recipientId)
      val groupRecipients = ArrayList<RecipientId>(groupRecords.size)
      for (groupRecord in groupRecords) {
        groupRecipients.add(groupRecord.recipientId)
      }
      consumer(groupRecipients)
    }
  }

  fun refreshRecipient(recipientId: RecipientId) {
    GenZappExecutors.UNBOUNDED.execute {
      try {
        ContactDiscovery.refresh(context, Recipient.resolved(recipientId), false)
      } catch (e: IOException) {
        Log.w(TAG, "Failed to refresh user after adding to contacts.")
      }
    }
  }

  fun setMuteUntil(recipientId: RecipientId, until: Long) {
    GenZappExecutors.BOUNDED.execute {
      GenZappDatabase.recipients.setMuted(recipientId, until)
    }
  }

  fun getGroupCapacity(groupId: GroupId, consumer: (GroupCapacityResult) -> Unit) {
    GenZappExecutors.BOUNDED.execute {
      val groupRecord: GroupRecord = GenZappDatabase.groups.getGroup(groupId).get()
      consumer(
        if (groupRecord.isV2Group) {
          val decryptedGroup: DecryptedGroup = groupRecord.requireV2GroupProperties().decryptedGroup
          val pendingMembers: List<RecipientId> = decryptedGroup.pendingMembers
            .map { m -> m.serviceIdBytes }
            .map { s -> GroupProtoUtil.serviceIdBinaryToRecipientId(s) }

          val members = mutableListOf<RecipientId>()

          members.addAll(groupRecord.members)
          members.addAll(pendingMembers)

          GroupCapacityResult(Recipient.self().id, members, RemoteConfig.groupLimits, groupRecord.isAnnouncementGroup)
        } else {
          GroupCapacityResult(Recipient.self().id, groupRecord.members, RemoteConfig.groupLimits, false)
        }
      )
    }
  }

  fun addMembers(groupId: GroupId, selected: List<RecipientId>, consumer: (GroupAddMembersResult) -> Unit) {
    groupManagementRepository.addMembers(groupId, selected, consumer)
  }

  fun setMuteUntil(groupId: GroupId, until: Long) {
    GenZappExecutors.BOUNDED.execute {
      val recipientId = Recipient.externalGroupExact(groupId).id
      GenZappDatabase.recipients.setMuted(recipientId, until)
    }
  }

  fun block(recipientId: RecipientId) {
    GenZappExecutors.BOUNDED.execute {
      val recipient = Recipient.resolved(recipientId)
      if (recipient.isGroup) {
        RecipientUtil.block(context, recipient)
      } else {
        RecipientUtil.blockNonGroup(context, recipient)
      }
    }
  }

  fun unblock(recipientId: RecipientId) {
    GenZappExecutors.BOUNDED.execute {
      val recipient = Recipient.resolved(recipientId)
      RecipientUtil.unblock(recipient)
    }
  }

  fun block(groupId: GroupId) {
    GenZappExecutors.BOUNDED.execute {
      val recipient = Recipient.externalGroupExact(groupId)
      RecipientUtil.block(context, recipient)
    }
  }

  fun unblock(groupId: GroupId) {
    GenZappExecutors.BOUNDED.execute {
      val recipient = Recipient.externalGroupExact(groupId)
      RecipientUtil.unblock(recipient)
    }
  }

  @WorkerThread
  fun isMessageRequestAccepted(recipient: Recipient): Boolean {
    return RecipientUtil.isMessageRequestAccepted(context, recipient)
  }

  fun getMembershipCountDescription(liveGroup: LiveGroup): LiveData<String> {
    return liveGroup.getMembershipCountDescription(context.resources)
  }

  fun getExternalPossiblyMigratedGroupRecipientId(groupId: GroupId, consumer: (RecipientId) -> Unit) {
    GenZappExecutors.BOUNDED.execute {
      consumer(Recipient.externalPossiblyMigratedGroup(groupId).id)
    }
  }
}
