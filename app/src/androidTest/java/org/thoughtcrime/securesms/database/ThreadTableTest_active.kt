/*
 * Copyright 2023 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.thoughtcrime.securesms.conversationlist.model.ConversationFilter
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testing.GenZappDatabaseRule
import org.whispersystems.GenZappservice.api.push.ServiceId.ACI
import java.util.UUID

@Suppress("ClassName")
class ThreadTableTest_active {

  @Rule
  @JvmField
  val databaseRule = GenZappDatabaseRule()

  private lateinit var recipient: Recipient

  @Before
  fun setUp() {
    recipient = Recipient.resolved(GenZappDatabase.recipients.getOrInsertFromServiceId(ACI.from(UUID.randomUUID())))
  }

  @Test
  fun givenActiveUnarchivedThread_whenIGetUnarchivedConversationList_thenIExpectThread() {
    val threadId = GenZappDatabase.threads.getOrCreateThreadIdFor(recipient)
    MmsHelper.insert(recipient = recipient, threadId = threadId)
    GenZappDatabase.threads.update(threadId, false)

    GenZappDatabase.threads.getUnarchivedConversationList(
      ConversationFilter.OFF,
      false,
      0,
      10
    ).use { threads ->
      assertEquals(1, threads.count)

      val record = ThreadTable.StaticReader(threads, InstrumentationRegistry.getInstrumentation().context).getNext()

      assertNotNull(record)
      assertEquals(record!!.recipient.id, recipient.id)
    }
  }

  @Test
  fun givenInactiveUnarchivedThread_whenIGetUnarchivedConversationList_thenIExpectNoThread() {
    val threadId = GenZappDatabase.threads.getOrCreateThreadIdFor(recipient)
    MmsHelper.insert(recipient = recipient, threadId = threadId)
    GenZappDatabase.threads.update(threadId, false)
    GenZappDatabase.threads.deleteConversation(threadId)

    GenZappDatabase.threads.getUnarchivedConversationList(
      ConversationFilter.OFF,
      false,
      0,
      10
    ).use { threads ->
      assertEquals(0, threads.count)
    }

    val threadId2 = GenZappDatabase.threads.getOrCreateThreadIdFor(recipient)
    assertEquals(threadId2, threadId)
  }

  @Test
  fun givenActiveArchivedThread_whenIGetUnarchivedConversationList_thenIExpectNoThread() {
    val threadId = GenZappDatabase.threads.getOrCreateThreadIdFor(recipient)
    MmsHelper.insert(recipient = recipient, threadId = threadId)
    GenZappDatabase.threads.update(threadId, false)
    GenZappDatabase.threads.setArchived(setOf(threadId), true)

    GenZappDatabase.threads.getUnarchivedConversationList(
      ConversationFilter.OFF,
      false,
      0,
      10
    ).use { threads ->
      assertEquals(0, threads.count)
    }
  }

  @Test
  fun givenActiveArchivedThread_whenIGetArchivedConversationList_thenIExpectThread() {
    val threadId = GenZappDatabase.threads.getOrCreateThreadIdFor(recipient)
    MmsHelper.insert(recipient = recipient, threadId = threadId)
    GenZappDatabase.threads.update(threadId, false)
    GenZappDatabase.threads.setArchived(setOf(threadId), true)

    GenZappDatabase.threads.getArchivedConversationList(
      ConversationFilter.OFF,
      0,
      10
    ).use { threads ->
      assertEquals(1, threads.count)
    }
  }

  @Test
  fun givenInactiveArchivedThread_whenIGetArchivedConversationList_thenIExpectNoThread() {
    val threadId = GenZappDatabase.threads.getOrCreateThreadIdFor(recipient)
    MmsHelper.insert(recipient = recipient, threadId = threadId)
    GenZappDatabase.threads.update(threadId, false)
    GenZappDatabase.threads.deleteConversation(threadId)
    GenZappDatabase.threads.setArchived(setOf(threadId), true)

    GenZappDatabase.threads.getArchivedConversationList(
      ConversationFilter.OFF,
      0,
      10
    ).use { threads ->
      assertEquals(0, threads.count)
    }

    val threadId2 = GenZappDatabase.threads.getOrCreateThreadIdFor(recipient)
    assertEquals(threadId2, threadId)
  }

  @Test
  fun givenActiveArchivedThread_whenIDeactivateThread_thenIExpectNoMessages() {
    val threadId = GenZappDatabase.threads.getOrCreateThreadIdFor(recipient)
    MmsHelper.insert(recipient = recipient, threadId = threadId)
    GenZappDatabase.threads.update(threadId, false)

    GenZappDatabase.messages.getConversation(threadId).use {
      assertEquals(1, it.count)
    }

    GenZappDatabase.threads.deleteConversation(threadId)

    GenZappDatabase.messages.getConversation(threadId).use {
      assertEquals(0, it.count)
    }
  }
}
