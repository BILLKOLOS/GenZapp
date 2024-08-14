package org.thoughtcrime.securesms.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.database.model.DistributionListId
import org.thoughtcrime.securesms.database.model.ParentStoryId
import org.thoughtcrime.securesms.database.model.StoryType
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.whispersystems.GenZappservice.api.push.ServiceId.ACI
import org.whispersystems.GenZappservice.api.push.ServiceId.PNI
import java.util.UUID
import java.util.concurrent.TimeUnit

@Suppress("ClassName")
@RunWith(AndroidJUnit4::class)
class MmsTableTest_stories {

  private lateinit var mms: MessageTable

  private val localAci = ACI.from(UUID.randomUUID())
  private val localPni = PNI.from(UUID.randomUUID())

  private lateinit var myStory: Recipient
  private lateinit var recipients: List<RecipientId>
  private lateinit var releaseChannelRecipient: Recipient

  @Before
  fun setUp() {
    mms = GenZappDatabase.messages

    mms.deleteAllThreads()

    GenZappStore.account.setAci(localAci)
    GenZappStore.account.setPni(localPni)

    myStory = Recipient.resolved(GenZappDatabase.recipients.getOrInsertFromDistributionListId(DistributionListId.MY_STORY))
    recipients = (0 until 5).map { GenZappDatabase.recipients.getOrInsertFromServiceId(ACI.from(UUID.randomUUID())) }
    releaseChannelRecipient = Recipient.resolved(GenZappDatabase.recipients.insertReleaseChannelRecipient())

    GenZappStore.releaseChannel.setReleaseChannelRecipientId(releaseChannelRecipient.id)
  }

  @Test
  fun givenNoStories_whenIGetOrderedStoryRecipientsAndIds_thenIExpectAnEmptyList() {
    // WHEN
    val result = mms.getOrderedStoryRecipientsAndIds(false)

    // THEN
    assertEquals(0, result.size)
  }

  @Test
  fun givenOneOutgoingAndOneIncomingStory_whenIGetOrderedStoryRecipientsAndIds_thenIExpectIncomingThenOutgoing() {
    // GIVEN
    val threadId = GenZappDatabase.threads.getOrCreateThreadIdFor(myStory)
    val sender = recipients[0]

    MmsHelper.insert(
      recipient = myStory,
      sentTimeMillis = 1,
      storyType = StoryType.STORY_WITH_REPLIES,
      threadId = threadId
    )

    MmsHelper.insert(
      IncomingMessage(
        type = MessageType.NORMAL,
        from = sender,
        sentTimeMillis = 2,
        serverTimeMillis = 2,
        receivedTimeMillis = 2,
        storyType = StoryType.STORY_WITH_REPLIES
      ),
      -1L
    )

    // WHEN
    val result = mms.getOrderedStoryRecipientsAndIds(false)

    // THEN
    assertEquals(listOf(sender.toLong(), myStory.id.toLong()), result.map { it.recipientId.toLong() })
  }

  @Test
  fun givenAStory_whenISetIncomingStoryMessageViewed_thenIExpectASetReceiptTimestamp() {
    // GIVEN
    val sender = recipients[0]
    val messageId = MmsHelper.insert(
      IncomingMessage(
        type = MessageType.NORMAL,
        from = sender,
        sentTimeMillis = 2,
        serverTimeMillis = 2,
        receivedTimeMillis = 2,
        storyType = StoryType.STORY_WITH_REPLIES
      ),
      -1L
    ).get().messageId

    val messageBeforeMark = GenZappDatabase.messages.getMessageRecord(messageId)
    assertFalse(messageBeforeMark.incomingStoryViewedAtTimestamp > 0)

    // WHEN
    GenZappDatabase.messages.setIncomingMessageViewed(messageId)

    // THEN
    val messageAfterMark = GenZappDatabase.messages.getMessageRecord(messageId)
    assertTrue(messageAfterMark.incomingStoryViewedAtTimestamp > 0)
  }

  @Ignore
  @Test
  fun given5ViewedStories_whenIGetOrderedStoryRecipientsAndIds_thenIExpectLatestViewedFirst() {
    // GIVEN
    val messageIds = recipients.take(5).map {
      MmsHelper.insert(
        IncomingMessage(
          type = MessageType.NORMAL,
          from = it,
          sentTimeMillis = 2,
          serverTimeMillis = 2,
          receivedTimeMillis = 2,
          storyType = StoryType.STORY_WITH_REPLIES
        ),
        -1L
      ).get().messageId
    }

    val randomizedOrderedIds = messageIds.shuffled()
    randomizedOrderedIds.forEach {
      GenZappDatabase.messages.setIncomingMessageViewed(it)
      Thread.sleep(5)
    }

    // WHEN
    val result = GenZappDatabase.messages.getOrderedStoryRecipientsAndIds(false)
    val resultOrderedIds = result.map { it.messageId }

    // THEN
    assertEquals(randomizedOrderedIds.reversed(), resultOrderedIds)
  }

  @Test
  fun given15Stories_whenIGetOrderedStoryRecipientsAndIds_thenIExpectUnviewedThenInterspersedViewedAndSelfSendsAllDescending() {
    val myStoryThread = GenZappDatabase.threads.getOrCreateThreadIdFor(myStory)

    val unviewedIds: List<Long> = (0 until 5).map {
      Thread.sleep(5)
      MmsHelper.insert(
        IncomingMessage(
          type = MessageType.NORMAL,
          from = recipients[it],
          sentTimeMillis = System.currentTimeMillis(),
          serverTimeMillis = 2,
          receivedTimeMillis = 2,
          storyType = StoryType.STORY_WITH_REPLIES
        ),
        -1L
      ).get().messageId
    }

    val viewedIds: List<Long> = (0 until 5).map {
      Thread.sleep(5)
      MmsHelper.insert(
        IncomingMessage(
          type = MessageType.NORMAL,
          from = recipients[it],
          sentTimeMillis = System.currentTimeMillis(),
          serverTimeMillis = 2,
          receivedTimeMillis = 2,
          storyType = StoryType.STORY_WITH_REPLIES
        ),
        -1L
      ).get().messageId
    }

    val interspersedIds: List<Long> = (0 until 10).map {
      Thread.sleep(5)
      if (it % 2 == 0) {
        GenZappDatabase.messages.setIncomingMessageViewed(viewedIds[it / 2])
        viewedIds[it / 2]
      } else {
        MmsHelper.insert(
          recipient = myStory,
          sentTimeMillis = System.currentTimeMillis(),
          storyType = StoryType.STORY_WITH_REPLIES,
          threadId = myStoryThread
        )
      }
    }

    val result = GenZappDatabase.messages.getOrderedStoryRecipientsAndIds(false)
    val resultOrderedIds = result.map { it.messageId }

    assertEquals(unviewedIds.reversed() + interspersedIds.reversed(), resultOrderedIds)
  }

  @Test
  fun givenNoStories_whenICheckIsOutgoingStoryAlreadyInDatabase_thenIExpectFalse() {
    // WHEN
    val result = mms.isOutgoingStoryAlreadyInDatabase(recipients[0], 200)

    // THEN
    assertFalse(result)
  }

  @Test
  fun givenNoOutgoingStories_whenICheckIsOutgoingStoryAlreadyInDatabase_thenIExpectFalse() {
    // GIVEN
    MmsHelper.insert(
      IncomingMessage(
        type = MessageType.NORMAL,
        from = recipients[0],
        sentTimeMillis = 200,
        serverTimeMillis = 2,
        receivedTimeMillis = 2,
        storyType = StoryType.STORY_WITH_REPLIES
      ),
      -1L
    )

    // WHEN
    val result = mms.isOutgoingStoryAlreadyInDatabase(recipients[0], 200)

    // THEN
    assertFalse(result)
  }

  @Test
  fun givenOutgoingStoryExistsForRecipientAndTime_whenICheckIsOutgoingStoryAlreadyInDatabase_thenIExpectTrue() {
    // GIVEN
    MmsHelper.insert(
      recipient = myStory,
      sentTimeMillis = 200,
      storyType = StoryType.STORY_WITH_REPLIES
    )

    // WHEN
    val result = mms.isOutgoingStoryAlreadyInDatabase(myStory.id, 200)

    // THEN
    assertTrue(result)
  }

  @Test
  fun givenAGroupStoryWithNoReplies_whenICheckHasSelfReplyInGroupStory_thenIExpectFalse() {
    // GIVEN
    val groupStoryId = MmsHelper.insert(
      recipient = myStory,
      sentTimeMillis = 200,
      storyType = StoryType.STORY_WITH_REPLIES
    )

    // WHEN
    val result = mms.hasGroupReplyOrReactionInStory(groupStoryId)

    // THEN
    assertFalse(result)
  }

  @Ignore
  @Test
  fun givenAGroupStoryWithAReplyFromSelf_whenICheckHasSelfReplyInGroupStory_thenIExpectTrue() {
    // GIVEN
    val groupStoryId = MmsHelper.insert(
      recipient = myStory,
      sentTimeMillis = 200,
      storyType = StoryType.STORY_WITH_REPLIES,
      threadId = -1L
    )

    MmsHelper.insert(
      recipient = myStory,
      sentTimeMillis = 201,
      storyType = StoryType.NONE,
      parentStoryId = ParentStoryId.GroupReply(groupStoryId)
    )

    // WHEN
    val result = mms.hasGroupReplyOrReactionInStory(groupStoryId)

    // THEN
    assertTrue(result)
  }

  @Test
  fun givenAGroupStoryWithAReactionFromSelf_whenICheckHasSelfReplyInGroupStory_thenIExpectTrue() {
    // GIVEN
    val groupStoryId = MmsHelper.insert(
      recipient = myStory,
      sentTimeMillis = 200,
      storyType = StoryType.STORY_WITH_REPLIES
    )

    MmsHelper.insert(
      recipient = myStory,
      sentTimeMillis = 201,
      storyType = StoryType.NONE,
      parentStoryId = ParentStoryId.GroupReply(groupStoryId),
      isStoryReaction = true
    )

    // WHEN
    val result = mms.hasGroupReplyOrReactionInStory(groupStoryId)

    // THEN
    assertTrue(result)
  }

  @Test
  fun givenAGroupStoryWithAReplyFromSomeoneElse_whenICheckHasSelfReplyInGroupStory_thenIExpectFalse() {
    // GIVEN
    val groupStoryId = MmsHelper.insert(
      recipient = myStory,
      sentTimeMillis = 200,
      storyType = StoryType.STORY_WITH_REPLIES
    )

    MmsHelper.insert(
      IncomingMessage(
        type = MessageType.NORMAL,
        from = myStory.id,
        sentTimeMillis = 201,
        serverTimeMillis = 201,
        receivedTimeMillis = 202,
        parentStoryId = ParentStoryId.GroupReply(groupStoryId)
      ),
      GenZappDatabase.threads.getOrCreateThreadIdFor(myStory, ThreadTable.DistributionTypes.DEFAULT)
    )

    // WHEN
    val result = mms.hasGroupReplyOrReactionInStory(groupStoryId)

    // THEN
    assertFalse(result)
  }

  @Test
  fun givenNotViewedOnboardingAndOnlyStoryIsOnboardingAndAdded2DaysAgo_whenIGetOldestStoryTimestamp_thenIExpectNull() {
    // GIVEN
    val threadId = GenZappDatabase.threads.getOrCreateThreadIdFor(releaseChannelRecipient)
    MmsHelper.insert(
      recipient = releaseChannelRecipient,
      sentTimeMillis = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2),
      storyType = StoryType.STORY_WITH_REPLIES,
      threadId = threadId
    )

    // WHEN
    val oldestTimestamp = GenZappDatabase.messages.getOldestStorySendTimestamp(false)

    // THEN
    assertNull(oldestTimestamp)
  }

  @Test
  fun givenViewedOnboardingAndOnlyStoryIsOnboardingAndAdded2DaysAgo_whenIGetOldestStoryTimestamp_thenIExpectNotNull() {
    // GIVEN
    val expected = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)
    val threadId = GenZappDatabase.threads.getOrCreateThreadIdFor(releaseChannelRecipient)
    MmsHelper.insert(
      recipient = releaseChannelRecipient,
      sentTimeMillis = expected,
      storyType = StoryType.STORY_WITH_REPLIES,
      threadId = threadId
    )

    // WHEN
    val oldestTimestamp = GenZappDatabase.messages.getOldestStorySendTimestamp(true)

    // THEN
    assertEquals(expected, oldestTimestamp)
  }
}
