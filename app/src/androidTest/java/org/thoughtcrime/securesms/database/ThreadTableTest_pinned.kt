package org.thoughtcrime.securesms.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.GenZapp.core.util.CursorUtil
import org.thoughtcrime.securesms.conversationlist.model.ConversationFilter
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testing.GenZappDatabaseRule
import org.whispersystems.GenZappservice.api.push.ServiceId.ACI
import java.util.UUID

@Suppress("ClassName")
class ThreadTableTest_pinned {

  @Rule
  @JvmField
  val databaseRule = GenZappDatabaseRule()

  private lateinit var recipient: Recipient

  @Before
  fun setUp() {
    recipient = Recipient.resolved(GenZappDatabase.recipients.getOrInsertFromServiceId(ACI.from(UUID.randomUUID())))
  }

  @Test
  fun givenAPinnedThread_whenIDeleteTheLastMessage_thenIDoNotDeleteOrUnpinTheThread() {
    // GIVEN
    val threadId = GenZappDatabase.threads.getOrCreateThreadIdFor(recipient)
    val messageId = MmsHelper.insert(recipient = recipient, threadId = threadId)
    GenZappDatabase.threads.pinConversations(listOf(threadId))

    // WHEN
    GenZappDatabase.messages.deleteMessage(messageId)

    // THEN
    val pinned = GenZappDatabase.threads.getPinnedThreadIds()
    assertTrue(threadId in pinned)
  }

  @Test
  fun givenAPinnedThread_whenIDeleteTheLastMessage_thenIExpectTheThreadInUnarchivedCount() {
    // GIVEN
    val threadId = GenZappDatabase.threads.getOrCreateThreadIdFor(recipient)
    val messageId = MmsHelper.insert(recipient = recipient, threadId = threadId)
    GenZappDatabase.threads.pinConversations(listOf(threadId))

    // WHEN
    GenZappDatabase.messages.deleteMessage(messageId)

    // THEN
    val unarchivedCount = GenZappDatabase.threads.getUnarchivedConversationListCount(ConversationFilter.OFF)
    assertEquals(1, unarchivedCount)
  }

  @Test
  fun givenAPinnedThread_whenIDeleteTheLastMessage_thenIExpectPinnedThreadInUnarchivedList() {
    // GIVEN
    val threadId = GenZappDatabase.threads.getOrCreateThreadIdFor(recipient)
    val messageId = MmsHelper.insert(recipient = recipient, threadId = threadId)
    GenZappDatabase.threads.pinConversations(listOf(threadId))

    // WHEN
    GenZappDatabase.messages.deleteMessage(messageId)

    // THEN
    GenZappDatabase.threads.getUnarchivedConversationList(ConversationFilter.OFF, true, 0, 1).use {
      it.moveToFirst()
      assertEquals(threadId, CursorUtil.requireLong(it, ThreadTable.ID))
    }
  }
}
