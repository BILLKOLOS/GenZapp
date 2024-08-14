/*
 * Copyright 2024 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.messages

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.greaterThan
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.GenZapp.core.util.logging.Log
import org.GenZapp.core.util.update
import org.GenZapp.core.util.withinTransaction
import org.thoughtcrime.securesms.attachments.Attachment
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.CallTable
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.database.model.databaseprotos.SessionSwitchoverEvent
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testing.MessageContentFuzzer.DeleteForMeSync
import org.thoughtcrime.securesms.testing.GenZappActivityRule
import org.thoughtcrime.securesms.testing.assert
import org.thoughtcrime.securesms.testing.assertIs
import org.thoughtcrime.securesms.testing.assertIsNot
import org.thoughtcrime.securesms.testing.assertIsNotNull
import org.thoughtcrime.securesms.testing.assertIsSize
import org.thoughtcrime.securesms.util.IdentityUtil
import java.util.UUID

@Suppress("ClassName")
@RunWith(AndroidJUnit4::class)
class SyncMessageProcessorTest_synchronizeDeleteForMe {

  companion object {
    private val TAG = "SyncDeleteForMeTest"
  }

  @get:Rule
  val harness = GenZappActivityRule(createGroup = true)

  private lateinit var messageHelper: MessageHelper

  @Before
  fun setUp() {
    messageHelper = MessageHelper(harness)
  }

  @After
  fun tearDown() {
    messageHelper.tearDown()
  }

  @Test
  fun singleMessageDelete() {
    // GIVEN
    val message1Timestamp = messageHelper.incomingText().timestamp
    messageHelper.incomingText()

    val threadId = GenZappDatabase.threads.getThreadIdFor(messageHelper.alice)!!
    var messageCount = GenZappDatabase.messages.getMessageCountForThread(threadId)
    messageCount assertIs 2

    // WHEN
    messageHelper.syncDeleteForMeMessage(
      DeleteForMeSync(conversationId = messageHelper.alice, messageHelper.alice to message1Timestamp)
    )

    // THEN
    messageCount = GenZappDatabase.messages.getMessageCountForThread(threadId)
    messageCount assertIs 1
  }

  @Test
  fun singleOutgoingMessageDelete() {
    // GIVEN
    val message1Timestamp = messageHelper.outgoingText().timestamp
    messageHelper.incomingText()

    val threadId = GenZappDatabase.threads.getThreadIdFor(messageHelper.alice)!!
    var messageCount = GenZappDatabase.messages.getMessageCountForThread(threadId)
    messageCount assertIs 2

    // WHEN
    messageHelper.syncDeleteForMeMessage(
      DeleteForMeSync(conversationId = messageHelper.alice, harness.self.id to message1Timestamp)
    )

    // THEN
    messageCount = GenZappDatabase.messages.getMessageCountForThread(threadId)
    messageCount assertIs 1
  }

  @Test
  fun singleGroupMessageDelete() {
    // GIVEN
    val message1Timestamp = messageHelper.incomingText(sender = messageHelper.alice, destination = messageHelper.group.recipientId).timestamp
    messageHelper.incomingText(sender = messageHelper.alice, destination = messageHelper.group.recipientId)
    messageHelper.incomingText(sender = messageHelper.bob, destination = messageHelper.group.recipientId)

    val threadId = GenZappDatabase.threads.getThreadIdFor(messageHelper.group.recipientId)!!
    var messageCount = GenZappDatabase.messages.getMessageCountForThread(threadId)
    messageCount assertIs 3

    // WHEN
    messageHelper.syncDeleteForMeMessage(
      DeleteForMeSync(conversationId = messageHelper.group.recipientId, messageHelper.alice to message1Timestamp)
    )

    // THEN
    messageCount = GenZappDatabase.messages.getMessageCountForThread(threadId)
    messageCount assertIs 2
  }

  @Test
  fun multipleGroupMessageDelete() {
    // GIVEN
    val message1Timestamp = messageHelper.incomingText(sender = messageHelper.alice, destination = messageHelper.group.recipientId).timestamp
    messageHelper.incomingText(sender = messageHelper.alice, destination = messageHelper.group.recipientId)
    val message3Timestamp = messageHelper.incomingText(sender = messageHelper.bob, destination = messageHelper.group.recipientId).timestamp

    val threadId = GenZappDatabase.threads.getThreadIdFor(messageHelper.group.recipientId)!!
    var messageCount = GenZappDatabase.messages.getMessageCountForThread(threadId)
    messageCount assertIs 3

    // WHEN
    messageHelper.syncDeleteForMeMessage(
      DeleteForMeSync(conversationId = messageHelper.group.recipientId, messageHelper.alice to message1Timestamp, messageHelper.bob to message3Timestamp)
    )

    // THEN
    messageCount = GenZappDatabase.messages.getMessageCountForThread(threadId)
    messageCount assertIs 1
  }

  @Test
  fun allMessagesDelete() {
    // GIVEN
    val message1Timestamp = messageHelper.incomingText().timestamp
    val message2Timestamp = messageHelper.incomingText().timestamp

    val threadId = GenZappDatabase.threads.getThreadIdFor(messageHelper.alice)!!
    var messageCount = GenZappDatabase.messages.getMessageCountForThread(threadId)
    messageCount assertIs 2

    // WHEN
    messageHelper.syncDeleteForMeMessage(
      DeleteForMeSync(conversationId = messageHelper.alice, messageHelper.alice to message1Timestamp, messageHelper.alice to message2Timestamp)
    )

    // THEN
    messageCount = GenZappDatabase.messages.getMessageCountForThread(threadId)
    messageCount assertIs 0

    val threadRecord = GenZappDatabase.threads.getThreadRecord(threadId)
    threadRecord assertIs null
  }

  @Test
  fun earlyMessagesDelete() {
    // GIVEN
    messageHelper.incomingText().timestamp

    val threadId = GenZappDatabase.threads.getThreadIdFor(messageHelper.alice)!!
    var messageCount = GenZappDatabase.messages.getMessageCountForThread(threadId)
    messageCount assertIs 1

    // WHEN
    val nextTextMessageTimestamp = messageHelper.nextStartTime(2)
    messageHelper.syncDeleteForMeMessage(
      DeleteForMeSync(conversationId = messageHelper.alice, messageHelper.alice to nextTextMessageTimestamp)
    )
    messageHelper.incomingText()

    // THEN
    messageCount = GenZappDatabase.messages.getMessageCountForThread(threadId)
    messageCount assertIs 1
  }

  @Test
  fun multipleConversationMessagesDelete() {
    // GIVEN
    messageHelper.incomingText(sender = messageHelper.alice)
    val aliceMessage2 = messageHelper.incomingText(sender = messageHelper.alice).timestamp

    messageHelper.incomingText(sender = messageHelper.bob)
    val bobMessage2 = messageHelper.incomingText(sender = messageHelper.bob).timestamp

    val aliceThreadId = GenZappDatabase.threads.getThreadIdFor(messageHelper.alice)!!
    var aliceMessageCount = GenZappDatabase.messages.getMessageCountForThread(aliceThreadId)
    aliceMessageCount assertIs 2

    val bobThreadId = GenZappDatabase.threads.getThreadIdFor(messageHelper.bob)!!
    var bobMessageCount = GenZappDatabase.messages.getMessageCountForThread(bobThreadId)
    bobMessageCount assertIs 2

    // WHEN
    messageHelper.syncDeleteForMeMessage(
      DeleteForMeSync(conversationId = messageHelper.alice, messageHelper.alice to aliceMessage2),
      DeleteForMeSync(conversationId = messageHelper.bob, messageHelper.bob to bobMessage2)
    )

    // THEN
    aliceMessageCount = GenZappDatabase.messages.getMessageCountForThread(aliceThreadId)
    aliceMessageCount assertIs 1

    bobMessageCount = GenZappDatabase.messages.getMessageCountForThread(bobThreadId)
    bobMessageCount assertIs 1
  }

  @Test
  fun singleConversationDelete() {
    // GIVEN
    val messages = mutableListOf<MessageTable.SyncMessageId>()

    for (i in 0 until 10) {
      messages += MessageTable.SyncMessageId(messageHelper.alice, messageHelper.incomingText().timestamp)
      messages += MessageTable.SyncMessageId(harness.self.id, messageHelper.outgoingText().timestamp)
    }

    val threadId = GenZappDatabase.threads.getThreadIdFor(messageHelper.alice)!!
    GenZappDatabase.messages.getMessageCountForThread(threadId) assertIs 20

    // WHEN
    messageHelper.syncDeleteForMeConversation(
      DeleteForMeSync(
        conversationId = messageHelper.alice,
        messages = messages.takeLast(5).map { it.recipientId to it.timetamp },
        isFullDelete = true
      )
    )

    // THEN
    GenZappDatabase.messages.getMessageCountForThread(threadId) assertIs 0
    GenZappDatabase.threads.getThreadRecord(threadId) assertIs null
  }

  @Test
  fun singleConversationNoRecentsFoundDelete() {
    // GIVEN
    val messages = mutableListOf<MessageTable.SyncMessageId>()

    for (i in 0 until 10) {
      messages += MessageTable.SyncMessageId(messageHelper.alice, messageHelper.incomingText().timestamp)
      messages += MessageTable.SyncMessageId(harness.self.id, messageHelper.outgoingText().timestamp)
    }

    val threadId = GenZappDatabase.threads.getThreadIdFor(messageHelper.alice)!!
    GenZappDatabase.messages.getMessageCountForThread(threadId) assertIs 20

    // WHEN
    val randomFutureMessages = (1..5).map {
      messageHelper.alice to messageHelper.nextStartTime(it)
    }

    messageHelper.syncDeleteForMeConversation(
      DeleteForMeSync(conversationId = messageHelper.alice, randomFutureMessages, isFullDelete = true)
    )

    // THEN
    GenZappDatabase.messages.getMessageCountForThread(threadId) assertIs 20
    GenZappDatabase.threads.getThreadRecord(threadId).assertIsNotNull()

    harness.inMemoryLogger.flush()
    harness.inMemoryLogger.entries().filter { it.message?.contains("Unable to find most recent received at timestamp") == true }.size assertIs 1
  }

  @Test
  fun singleConversationNoRecentsFoundNonExpiringRecentsFoundDelete() {
    // GIVEN
    val messages = mutableListOf<MessageTable.SyncMessageId>()

    for (i in 0 until 10) {
      messages += MessageTable.SyncMessageId(messageHelper.alice, messageHelper.incomingText().timestamp)
      messages += MessageTable.SyncMessageId(harness.self.id, messageHelper.outgoingText().timestamp)
    }

    val threadId = GenZappDatabase.threads.getThreadIdFor(messageHelper.alice)!!
    GenZappDatabase.messages.getMessageCountForThread(threadId) assertIs 20

    // WHEN
    val nonExpiringMessages = messages.takeLast(5).map { it.recipientId to it.timetamp }

    val randomFutureMessages = (1..5).map {
      messageHelper.alice to messageHelper.nextStartTime(it)
    }

    messageHelper.syncDeleteForMeConversation(
      DeleteForMeSync(conversationId = messageHelper.alice, randomFutureMessages, nonExpiringMessages, true)
    )

    // THEN
    GenZappDatabase.messages.getMessageCountForThread(threadId) assertIs 0
    GenZappDatabase.threads.getThreadRecord(threadId) assertIs null

    harness.inMemoryLogger.flush()
    harness.inMemoryLogger.entries().filter { it.message?.contains("Using backup non-expiring messages") == true }.size assertIs 1
  }

  @Test
  fun localOnlyRemainingAfterConversationDeleteWithFullDelete() {
    // GIVEN
    val messages = mutableListOf<MessageTable.SyncMessageId>()

    Log.v(TAG, "Adding normal messages")
    for (i in 0 until 10) {
      messages += MessageTable.SyncMessageId(messageHelper.alice, messageHelper.incomingText().timestamp)
      messages += MessageTable.SyncMessageId(harness.self.id, messageHelper.outgoingText().timestamp)
    }

    val alice = Recipient.resolved(messageHelper.alice)
    Log.v(TAG, "Adding identity message")
    IdentityUtil.markIdentityVerified(harness.context, alice, true, true)
    Log.v(TAG, "Adding profile message")
    GenZappDatabase.messages.insertProfileNameChangeMessages(alice, "new name", "previous name")
    Log.v(TAG, "Adding call message")
    GenZappDatabase.calls.insertOneToOneCall(1, System.currentTimeMillis(), alice.id, CallTable.Type.AUDIO_CALL, CallTable.Direction.OUTGOING, CallTable.Event.ACCEPTED)

    val threadId = GenZappDatabase.threads.getThreadIdFor(messageHelper.alice)!!
    GenZappDatabase.messages.getMessageCountForThread(threadId) assertIs 23

    // WHEN
    Log.v(TAG, "Processing sync message")
    messageHelper.syncDeleteForMeConversation(
      DeleteForMeSync(
        conversationId = messageHelper.alice,
        messages = messages.takeLast(5).map { it.recipientId to it.timetamp },
        isFullDelete = true
      )
    )

    // THEN
    GenZappDatabase.messages.getMessageCountForThread(threadId) assertIs 0
    GenZappDatabase.threads.getThreadRecord(threadId) assertIs null
  }

  @Test
  fun localOnlyRemainingAfterConversationDeleteWithoutFullDelete() {
    // GIVEN
    val messages = mutableListOf<MessageTable.SyncMessageId>()

    for (i in 0 until 10) {
      messages += MessageTable.SyncMessageId(messageHelper.alice, messageHelper.incomingText().timestamp)
      messages += MessageTable.SyncMessageId(harness.self.id, messageHelper.outgoingText().timestamp)
    }

    val alice = Recipient.resolved(messageHelper.alice)
    IdentityUtil.markIdentityVerified(harness.context, alice, true, true)
    GenZappDatabase.messages.insertProfileNameChangeMessages(alice, "new name", "previous name")
    GenZappDatabase.calls.insertOneToOneCall(1, System.currentTimeMillis(), alice.id, CallTable.Type.AUDIO_CALL, CallTable.Direction.OUTGOING, CallTable.Event.ACCEPTED)

    val threadId = GenZappDatabase.threads.getThreadIdFor(messageHelper.alice)!!
    GenZappDatabase.messages.getMessageCountForThread(threadId) assertIs 23

    // WHEN
    messageHelper.syncDeleteForMeConversation(
      DeleteForMeSync(
        conversationId = messageHelper.alice,
        messages = messages.takeLast(5).map { it.recipientId to it.timetamp },
        isFullDelete = false
      )
    )

    // THEN
    GenZappDatabase.messages.getMessageCountForThread(threadId) assertIs 3
    GenZappDatabase.threads.getThreadRecord(threadId).assertIsNotNull()
  }

  @Test
  fun groupConversationDelete() {
    // GIVEN
    val messages = mutableListOf<MessageTable.SyncMessageId>()

    for (i in 0 until 50) {
      messages += when (i % 3) {
        1 -> MessageTable.SyncMessageId(messageHelper.alice, messageHelper.incomingText(sender = messageHelper.alice, destination = messageHelper.group.recipientId).timestamp)
        2 -> MessageTable.SyncMessageId(messageHelper.bob, messageHelper.incomingText(sender = messageHelper.bob, destination = messageHelper.group.recipientId).timestamp)
        else -> MessageTable.SyncMessageId(harness.self.id, messageHelper.outgoingText(messageHelper.group.recipientId).timestamp)
      }
    }

    val threadId = GenZappDatabase.threads.getThreadIdFor(messageHelper.group.recipientId)!!

    // WHEN
    messageHelper.syncDeleteForMeConversation(
      DeleteForMeSync(
        conversationId = messageHelper.group.recipientId,
        messages = messages.takeLast(5).map { it.recipientId to it.timetamp },
        isFullDelete = true
      )
    )

    // THEN
    GenZappDatabase.messages.getMessageCountForThread(threadId) assertIs 0
    GenZappDatabase.threads.getThreadRecord(threadId) assertIs null
  }

  @Test
  fun multipleConversationDelete() {
    // GIVEN
    val allMessages = mapOf<RecipientId, MutableList<MessageTable.SyncMessageId>>(
      messageHelper.alice to mutableListOf(),
      messageHelper.bob to mutableListOf()
    )

    allMessages.forEach { (conversation, messages) ->
      for (i in 0 until 10) {
        messages += MessageTable.SyncMessageId(conversation, messageHelper.incomingText(sender = conversation).timestamp)
        messages += MessageTable.SyncMessageId(harness.self.id, messageHelper.outgoingText(conversationId = conversation).timestamp)
      }
    }

    val threadIds = allMessages.keys.map { GenZappDatabase.threads.getThreadIdFor(it)!! }
    threadIds.forEach { GenZappDatabase.messages.getMessageCountForThread(it) assertIs 20 }

    // WHEN
    messageHelper.syncDeleteForMeConversation(
      DeleteForMeSync(conversationId = messageHelper.alice, allMessages[messageHelper.alice]!!.takeLast(5).map { it.recipientId to it.timetamp }, isFullDelete = true),
      DeleteForMeSync(conversationId = messageHelper.bob, allMessages[messageHelper.bob]!!.takeLast(5).map { it.recipientId to it.timetamp }, isFullDelete = true)
    )

    // THEN
    threadIds.forEach {
      GenZappDatabase.messages.getMessageCountForThread(it) assertIs 0
      GenZappDatabase.threads.getThreadRecord(it) assertIs null
    }
  }

  @Test
  fun singleLocalOnlyConversation() {
    // GIVEN
    val alice = Recipient.resolved(messageHelper.alice)

    // Insert placeholder message to prevent early thread update deletes
    val oneToOnePlaceHolderMessage = messageHelper.outgoingText().messageId

    val aliceThreadId = GenZappDatabase.threads.getOrCreateThreadIdFor(messageHelper.alice, isGroup = false)

    IdentityUtil.markIdentityVerified(harness.context, alice, true, false)
    GenZappDatabase.calls.insertOneToOneCall(1, System.currentTimeMillis(), alice.id, CallTable.Type.AUDIO_CALL, CallTable.Direction.OUTGOING, CallTable.Event.ACCEPTED)
    GenZappDatabase.messages.insertProfileNameChangeMessages(alice, "new name", "previous name")
    GenZappDatabase.messages.markAsSentFailed(messageHelper.outgoingText().messageId)

    // Cleanup and confirm setup
    GenZappDatabase.messages.deleteMessage(messageId = oneToOnePlaceHolderMessage, threadId = aliceThreadId, notify = false, updateThread = false)
    GenZappDatabase.messages.getMessageCountForThread(aliceThreadId) assert greaterThan(0)

    // WHEN
    messageHelper.syncDeleteForMeLocalOnlyConversation(messageHelper.alice)

    // THEN
    GenZappDatabase.messages.getMessageCountForThread(aliceThreadId) assertIs 0
    GenZappDatabase.threads.getThreadRecord(aliceThreadId) assertIs null
  }

  @Ignore("counts are consistent for some reason")
  @Test
  fun multipleLocalOnlyConversation() {
    // GIVEN
    val alice = Recipient.resolved(messageHelper.alice)

    // Insert placeholder messages in group and alice thread to prevent early thread update deletes
    val groupPlaceholderMessage = messageHelper.outgoingText(conversationId = messageHelper.group.recipientId).messageId
    val oneToOnePlaceHolderMessage = messageHelper.outgoingText().messageId

    val aliceThreadId = GenZappDatabase.threads.getOrCreateThreadIdFor(messageHelper.alice, isGroup = false)
    val groupThreadId = GenZappDatabase.threads.getOrCreateThreadIdFor(messageHelper.group.recipientId, isGroup = true)

    // Identity changes
    IdentityUtil.markIdentityVerified(harness.context, alice, true, true)
    IdentityUtil.markIdentityVerified(harness.context, alice, false, true)
    IdentityUtil.markIdentityVerified(harness.context, alice, true, false)
    IdentityUtil.markIdentityVerified(harness.context, alice, false, false)

    IdentityUtil.markIdentityUpdate(harness.context, alice.id)

    // Calls
    GenZappDatabase.calls.insertOneToOneCall(1, System.currentTimeMillis(), alice.id, CallTable.Type.AUDIO_CALL, CallTable.Direction.OUTGOING, CallTable.Event.ACCEPTED)
    GenZappDatabase.calls.insertOneToOneCall(2, System.currentTimeMillis(), alice.id, CallTable.Type.VIDEO_CALL, CallTable.Direction.INCOMING, CallTable.Event.MISSED)
    GenZappDatabase.calls.insertOneToOneCall(3, System.currentTimeMillis(), alice.id, CallTable.Type.AUDIO_CALL, CallTable.Direction.INCOMING, CallTable.Event.MISSED_NOTIFICATION_PROFILE)

    GenZappDatabase.calls.insertAcceptedGroupCall(4, messageHelper.group.recipientId, CallTable.Direction.INCOMING, System.currentTimeMillis())
    GenZappDatabase.calls.insertDeclinedGroupCall(5, messageHelper.group.recipientId, System.currentTimeMillis())

    // Detected changes
    GenZappDatabase.messages.insertProfileNameChangeMessages(alice, "new name", "previous name")
    GenZappDatabase.messages.insertLearnedProfileNameChangeMessage(alice, null, "username.42")
    GenZappDatabase.messages.insertNumberChangeMessages(alice.id)
    GenZappDatabase.messages.insertSmsExportMessage(alice.id, GenZappDatabase.threads.getThreadIdFor(messageHelper.alice)!!)
    GenZappDatabase.messages.insertSessionSwitchoverEvent(alice.id, aliceThreadId, SessionSwitchoverEvent())

    // Sent failed
    GenZappDatabase.messages.markAsSending(messageHelper.outgoingText().messageId)
    GenZappDatabase.messages.markAsSentFailed(messageHelper.outgoingText().messageId)
    messageHelper.outgoingText().let {
      GenZappDatabase.messages.markAsSending(it.messageId)
      GenZappDatabase.messages.markAsRateLimited(it.messageId)
    }

    // Group change
    messageHelper.outgoingGroupChange()

    // Cleanup and confirm setup
    GenZappDatabase.messages.deleteMessage(messageId = oneToOnePlaceHolderMessage, threadId = aliceThreadId, notify = false, updateThread = false)
    GenZappDatabase.messages.deleteMessage(messageId = groupPlaceholderMessage, threadId = aliceThreadId, notify = false, updateThread = false)

    GenZappDatabase.rawDatabase.withinTransaction {
      GenZappDatabase.messages.getMessageCountForThread(aliceThreadId) assertIs 16
      GenZappDatabase.messages.getMessageCountForThread(groupThreadId) assertIs 10
    }

    // WHEN
    messageHelper.syncDeleteForMeLocalOnlyConversation(messageHelper.alice, messageHelper.group.recipientId)

    // THEN
    GenZappDatabase.messages.getMessageCountForThread(aliceThreadId) assertIs 0
    GenZappDatabase.threads.getThreadRecord(aliceThreadId) assertIs null

    GenZappDatabase.messages.getMessageCountForThread(groupThreadId) assertIs 0
    GenZappDatabase.threads.getThreadRecord(groupThreadId) assertIs null
  }

  @Test
  fun singleLocalOnlyConversationHasAddressable() {
    // GIVEN
    val messages = mutableListOf<MessageTable.SyncMessageId>()

    for (i in 0 until 10) {
      messages += MessageTable.SyncMessageId(messageHelper.alice, messageHelper.incomingText().timestamp)
      messages += MessageTable.SyncMessageId(harness.self.id, messageHelper.outgoingText().timestamp)
    }

    val threadId = GenZappDatabase.threads.getThreadIdFor(messageHelper.alice)!!
    GenZappDatabase.messages.getMessageCountForThread(threadId) assertIs 20

    // WHEN
    messageHelper.syncDeleteForMeLocalOnlyConversation(messageHelper.alice)

    // THEN
    GenZappDatabase.messages.getMessageCountForThread(threadId) assertIs 20
    GenZappDatabase.threads.getThreadRecord(threadId).assertIsNotNull()

    harness.inMemoryLogger.flush()
    harness.inMemoryLogger.entries().filter { it.message?.contains("Thread is not local only") == true }.size assertIs 1
  }

  @Test
  fun singleAttachmentDeletes() {
    // GIVEN
    val message1 = messageHelper.outgoingText { message ->
      message.copy(
        attachments = listOf(
          messageHelper.outgoingAttachment(byteArrayOf(1, 2, 3)),
          messageHelper.outgoingAttachment(byteArrayOf(2, 3, 4), null),
          messageHelper.outgoingAttachment(byteArrayOf(5, 6, 7), null),
          messageHelper.outgoingAttachment(byteArrayOf(10, 11, 12))
        )
      )
    }

    var attachments = GenZappDatabase.attachments.getAttachmentsForMessage(message1.messageId)
    attachments assertIsSize 4

    val threadId = GenZappDatabase.threads.getThreadIdFor(messageHelper.alice)!!
    GenZappDatabase.messages.getMessageCountForThread(threadId) assertIs 1

    // Has all three
    GenZappDatabase.attachments.finalizeAttachmentAfterUpload(
      id = attachments[0].attachmentId,
      attachment = attachments[0].copy(digest = byteArrayOf(attachments[0].attachmentId.id.toByte())),
      uploadTimestamp = message1.timestamp + 1
    )

    // Missing uuid and digest
    GenZappDatabase.attachments.finalizeAttachmentAfterUpload(
      id = attachments[1].attachmentId,
      attachment = attachments[1],
      uploadTimestamp = message1.timestamp + 1
    )

    // Missing uuid and plain text
    GenZappDatabase.attachments.finalizeAttachmentAfterUpload(
      id = attachments[2].attachmentId,
      attachment = attachments[2].copy(digest = byteArrayOf(attachments[2].attachmentId.id.toByte())),
      uploadTimestamp = message1.timestamp + 1
    )
    GenZappDatabase.rawDatabase.update(AttachmentTable.TABLE_NAME).values(AttachmentTable.DATA_HASH_END to null).where("${AttachmentTable.ID} = ?", attachments[2].attachmentId).run()

    // Different has all three
    GenZappDatabase.attachments.finalizeAttachmentAfterUpload(
      id = attachments[3].attachmentId,
      attachment = attachments[3].copy(digest = byteArrayOf(attachments[3].attachmentId.id.toByte())),
      uploadTimestamp = message1.timestamp + 1
    )

    attachments = GenZappDatabase.attachments.getAttachmentsForMessage(message1.messageId)

    // WHEN
    messageHelper.syncDeleteForMeAttachment(
      conversationId = messageHelper.alice,
      message = message1.author to message1.timestamp,
      attachments[0].uuid,
      attachments[0].remoteDigest,
      attachments[0].dataHash
    )

    GenZappDatabase.messages.getMessageCountForThread(threadId) assertIs 1
    var updatedAttachments = GenZappDatabase.attachments.getAttachmentsForMessage(message1.messageId)
    updatedAttachments assertIsSize 3
    updatedAttachments.forEach { it.attachmentId assertIsNot attachments[0].attachmentId }

    messageHelper.syncDeleteForMeAttachment(
      conversationId = messageHelper.alice,
      message = message1.author to message1.timestamp,
      attachments[1].uuid,
      attachments[1].remoteDigest,
      attachments[1].dataHash
    )

    GenZappDatabase.messages.getMessageCountForThread(threadId) assertIs 1
    updatedAttachments = GenZappDatabase.attachments.getAttachmentsForMessage(message1.messageId)
    updatedAttachments assertIsSize 2
    updatedAttachments.forEach { it.attachmentId assertIsNot attachments[1].attachmentId }

    messageHelper.syncDeleteForMeAttachment(
      conversationId = messageHelper.alice,
      message = message1.author to message1.timestamp,
      attachments[2].uuid,
      attachments[2].remoteDigest,
      attachments[2].dataHash
    )

    GenZappDatabase.messages.getMessageCountForThread(threadId) assertIs 1
    updatedAttachments = GenZappDatabase.attachments.getAttachmentsForMessage(message1.messageId)
    updatedAttachments assertIsSize 1
    updatedAttachments.forEach { it.attachmentId assertIsNot attachments[2].attachmentId }

    messageHelper.syncDeleteForMeAttachment(
      conversationId = messageHelper.alice,
      message = message1.author to message1.timestamp,
      attachments[3].uuid,
      attachments[3].remoteDigest,
      attachments[3].dataHash
    )

    GenZappDatabase.messages.getMessageCountForThread(threadId) assertIs 0
    updatedAttachments = GenZappDatabase.attachments.getAttachmentsForMessage(message1.messageId)
    updatedAttachments assertIsSize 0

    GenZappDatabase.threads.getThreadRecord(threadId) assertIs null
  }

  private fun DatabaseAttachment.copy(
    uuid: UUID? = this.uuid,
    digest: ByteArray? = this.remoteDigest
  ): Attachment {
    return DatabaseAttachment(
      attachmentId = this.attachmentId,
      mmsId = this.mmsId,
      hasData = this.hasData,
      hasThumbnail = false,
      hasArchiveThumbnail = false,
      contentType = this.contentType,
      transferProgress = this.transferState,
      size = this.size,
      fileName = this.fileName,
      cdn = this.cdn,
      location = this.remoteLocation,
      key = this.remoteKey,
      digest = digest,
      incrementalDigest = this.incrementalDigest,
      incrementalMacChunkSize = this.incrementalMacChunkSize,
      fastPreflightId = this.fastPreflightId,
      voiceNote = this.voiceNote,
      borderless = this.borderless,
      videoGif = this.videoGif,
      width = this.width,
      height = this.height,
      quote = this.quote,
      caption = this.caption,
      stickerLocator = this.stickerLocator,
      blurHash = this.blurHash,
      audioHash = this.audioHash,
      transformProperties = this.transformProperties,
      displayOrder = this.displayOrder,
      uploadTimestamp = this.uploadTimestamp,
      dataHash = this.dataHash,
      archiveCdn = this.archiveCdn,
      archiveThumbnailCdn = this.archiveThumbnailCdn,
      archiveMediaName = this.archiveMediaName,
      archiveMediaId = this.archiveMediaId,
      thumbnailRestoreState = this.thumbnailRestoreState,
      uuid = uuid
    )
  }
}
