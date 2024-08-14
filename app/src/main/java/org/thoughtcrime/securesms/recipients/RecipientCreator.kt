package org.thoughtcrime.securesms.recipients

import android.content.Context
import androidx.annotation.VisibleForTesting
import org.thoughtcrime.securesms.conversation.colors.AvatarColor
import org.thoughtcrime.securesms.database.RecipientTable.RegisteredState
import org.thoughtcrime.securesms.database.model.GroupRecord
import org.thoughtcrime.securesms.database.model.RecipientRecord
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.thoughtcrime.securesms.util.Util
import java.util.LinkedList
import java.util.Optional

/**
 * [Recipient] is a very large class with a lot of fields. This class distributes some of the burden in creating that object.
 * It's also helpful for java-kotlin interop, since there's so many optional fields.
 */
object RecipientCreator {
  @JvmOverloads
  @JvmStatic
  fun forId(recipientId: RecipientId, resolved: Boolean = false): Recipient {
    return Recipient(recipientId, isResolving = !resolved)
  }

  @JvmStatic
  fun forIndividual(context: Context, record: RecipientRecord): Recipient {
    val isSelf = record.e164 != null && record.e164 == GenZappStore.account.e164 || record.aci != null && record.aci == GenZappStore.account.aci
    val isReleaseChannel = record.id == GenZappStore.releaseChannel.releaseChannelRecipientId
    var registeredState = record.registered

    if (isSelf) {
      registeredState = if (GenZappStore.account.isRegistered && !TextSecurePreferences.isUnauthorizedReceived(context)) {
        RegisteredState.REGISTERED
      } else {
        RegisteredState.NOT_REGISTERED
      }
    }

    return create(
      resolved = true,
      groupName = null,
      systemContactName = record.systemDisplayName,
      isSelf = isSelf,
      registeredState = registeredState,
      record = record,
      participantIds = null,
      isReleaseChannel = isReleaseChannel,
      avatarColor = null,
      groupRecord = Optional.empty()
    )
  }

  @JvmOverloads
  @JvmStatic
  fun forGroup(groupRecord: GroupRecord, recipientRecord: RecipientRecord, resolved: Boolean = true): Recipient {
    return create(
      resolved = resolved,
      groupName = groupRecord.title,
      systemContactName = null,
      isSelf = false,
      registeredState = recipientRecord.registered,
      record = recipientRecord,
      participantIds = groupRecord.members,
      isReleaseChannel = false,
      avatarColor = null,
      groupRecord = Optional.of(groupRecord)
    )
  }

  @JvmStatic
  fun forDistributionList(title: String?, members: List<RecipientId>?, record: RecipientRecord): Recipient {
    return create(
      resolved = true,
      groupName = title,
      systemContactName = null,
      isSelf = false,
      registeredState = record.registered,
      record = record,
      participantIds = members,
      isReleaseChannel = false,
      avatarColor = null,
      groupRecord = Optional.empty()
    )
  }

  @JvmStatic
  fun forCallLink(name: String?, record: RecipientRecord, avatarColor: AvatarColor): Recipient {
    return create(
      resolved = true,
      groupName = name,
      systemContactName = null,
      isSelf = false,
      registeredState = record.registered,
      record = record,
      participantIds = emptyList(),
      isReleaseChannel = false,
      avatarColor = avatarColor,
      groupRecord = Optional.empty()
    )
  }

  @JvmStatic
  fun forUnknown(): Recipient {
    return Recipient.UNKNOWN
  }

  @JvmStatic
  fun forUnknownGroup(id: RecipientId, groupId: GroupId?): Recipient {
    return Recipient(
      id = id,
      isResolving = true,
      groupIdValue = groupId
    )
  }

  @VisibleForTesting
  fun create(
    resolved: Boolean,
    groupName: String?,
    systemContactName: String?,
    isSelf: Boolean,
    registeredState: RegisteredState,
    record: RecipientRecord,
    participantIds: List<RecipientId>?,
    isReleaseChannel: Boolean,
    avatarColor: AvatarColor?,
    groupRecord: Optional<GroupRecord>
  ): Recipient {
    return Recipient(
      id = record.id,
      isResolving = !resolved,
      groupAvatarId = groupRecord.map { if (it.hasAvatar()) it.avatarId else null },
      systemContactPhoto = Util.uri(record.systemContactPhotoUri),
      customLabel = record.systemPhoneLabel,
      contactUri = Util.uri(record.systemContactUri),
      aciValue = record.aci,
      pniValue = record.pni,
      usernameValue = record.username,
      e164Value = record.e164,
      emailValue = record.email,
      groupIdValue = record.groupId,
      distributionListIdValue = record.distributionListId,
      messageRingtoneUri = record.messageRingtone,
      callRingtoneUri = record.callRingtone,
      muteUntil = record.muteUntil,
      messageVibrate = record.messageVibrateState,
      callVibrate = record.callVibrateState,
      isBlocked = record.isBlocked,
      expiresInSeconds = record.expireMessages,
      participantIdsValue = participantIds ?: LinkedList(),
      isActiveGroup = groupRecord.map { it.isActive }.orElse(false),
      profileName = record.GenZappProfileName,
      registeredValue = registeredState,
      profileKey = record.profileKey,
      expiringProfileKeyCredential = record.expiringProfileKeyCredential,
      profileAvatar = record.GenZappProfileAvatar,
      profileAvatarFileDetails = record.profileAvatarFileDetails,
      isProfileSharing = record.profileSharing,
      hiddenState = record.hiddenState,
      lastProfileFetchTime = record.lastProfileFetch,
      isSelf = isSelf,
      notificationChannelValue = record.notificationChannel,
      sealedSenderAccessModeValue = record.sealedSenderAccessMode,
      capabilities = record.capabilities,
      storageId = record.storageId,
      mentionSetting = record.mentionSetting,
      wallpaperValue = record.wallpaper,
      chatColorsValue = record.chatColors,
      avatarColor = avatarColor ?: record.avatarColor,
      about = record.about,
      aboutEmoji = record.aboutEmoji,
      systemProfileName = record.systemProfileName,
      groupName = groupName,
      systemContactName = systemContactName,
      extras = Optional.ofNullable(record.extras),
      hasGroupsInCommon = record.hasGroupsInCommon,
      badges = record.badges,
      isReleaseNotes = isReleaseChannel,
      needsPniSignature = record.needsPniSignature,
      callLinkRoomId = record.callLinkRoomId,
      groupRecord = groupRecord,
      phoneNumberSharing = record.phoneNumberSharing,
      nickname = record.nickname,
      note = record.note
    )
  }
}
