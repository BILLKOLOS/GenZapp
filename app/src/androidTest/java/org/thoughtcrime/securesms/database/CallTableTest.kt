package org.thoughtcrime.securesms.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.GenZapp.ringrtc.CallId
import org.GenZapp.ringrtc.CallManager
import org.thoughtcrime.securesms.calls.log.CallLogFilter
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testing.GenZappActivityRule

@RunWith(AndroidJUnit4::class)
class CallTableTest {

  @get:Rule
  val harness = GenZappActivityRule(createGroup = true)

  private val groupRecipientId: RecipientId
    get() = harness.group!!.recipientId

  @Test
  fun givenACall_whenISetTimestamp_thenIExpectUpdatedTimestamp() {
    val callId = 1L
    val now = System.currentTimeMillis()
    GenZappDatabase.calls.insertAcceptedGroupCall(
      callId,
      groupRecipientId,
      CallTable.Direction.INCOMING,
      now
    )

    GenZappDatabase.calls.setTimestamp(callId, groupRecipientId, -1L)
    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(-1L, call?.timestamp)

    val messageRecord = GenZappDatabase.messages.getMessageRecord(call!!.messageId!!)
    assertEquals(-1L, messageRecord.dateReceived)
    assertEquals(-1L, messageRecord.dateSent)
  }

  @Test
  fun givenPreExistingEvent_whenIDeleteGroupCall_thenIMarkDeletedAndSetTimestamp() {
    val callId = 1L
    val now = System.currentTimeMillis()
    GenZappDatabase.calls.insertAcceptedGroupCall(
      callId,
      groupRecipientId,
      CallTable.Direction.INCOMING,
      now
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    GenZappDatabase.calls.markCallDeletedFromSyncEvent(call!!)

    val deletedCall = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    val oldestDeletionTimestamp = GenZappDatabase.calls.getOldestDeletionTimestamp()

    assertEquals(CallTable.Event.DELETE, deletedCall?.event)
    assertNotEquals(0L, oldestDeletionTimestamp)
    assertNull(deletedCall!!.messageId)
  }

  @Test
  fun givenNoPreExistingEvent_whenIDeleteGroupCall_thenIInsertAndMarkCallDeleted() {
    val callId = 1L
    GenZappDatabase.calls.insertDeletedCallFromSyncEvent(
      callId,
      groupRecipientId,
      CallTable.Type.GROUP_CALL,
      CallTable.Direction.OUTGOING,
      System.currentTimeMillis()
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)

    val oldestDeletionTimestamp = GenZappDatabase.calls.getOldestDeletionTimestamp()

    assertEquals(CallTable.Event.DELETE, call?.event)
    assertNotEquals(oldestDeletionTimestamp, 0)
    assertNull(call?.messageId)
  }

  @Test
  fun givenNoPriorEvent_whenIInsertAcceptedOutgoingGroupCall_thenIExpectLocalRingerAndOutgoingRing() {
    val callId = 1L
    GenZappDatabase.calls.insertAcceptedGroupCall(
      callId,
      groupRecipientId,
      CallTable.Direction.OUTGOING,
      System.currentTimeMillis()
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.OUTGOING_RING, call?.event)
    assertEquals(harness.self.id, call?.ringerRecipient)
    assertNotNull(call?.messageId)
  }

  @Test
  fun givenNoPriorEvent_whenIInsertAcceptedIncomingGroupCall_thenIExpectJoined() {
    val callId = 1L
    GenZappDatabase.calls.insertAcceptedGroupCall(
      callId,
      groupRecipientId,
      CallTable.Direction.INCOMING,
      System.currentTimeMillis()
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.JOINED, call?.event)
    assertNull(call?.ringerRecipient)
    assertNotNull(call?.messageId)
  }

  @Test
  fun givenARingingCall_whenIAcceptedIncomingGroupCall_thenIExpectAccepted() {
    val callId = 1L
    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      ringId = callId,
      groupRecipientId = groupRecipientId,
      ringerRecipient = harness.others[1],
      dateReceived = System.currentTimeMillis(),
      ringState = CallManager.RingUpdate.REQUESTED
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.RINGING, call?.event)

    GenZappDatabase.calls.acceptIncomingGroupCall(
      call!!
    )

    val acceptedCall = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertEquals(CallTable.Event.ACCEPTED, acceptedCall?.event)
  }

  @Test
  fun givenAMissedCall_whenIAcceptedIncomingGroupCall_thenIExpectAccepted() {
    val callId = 1L
    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      ringId = callId,
      groupRecipientId = groupRecipientId,
      ringerRecipient = harness.others[1],
      dateReceived = System.currentTimeMillis(),
      ringState = CallManager.RingUpdate.EXPIRED_REQUEST
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.MISSED, call?.event)

    GenZappDatabase.calls.acceptIncomingGroupCall(
      call!!
    )

    val acceptedCall = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertEquals(CallTable.Event.ACCEPTED, acceptedCall?.event)
  }

  @Test
  fun givenADeclinedCall_whenIAcceptedIncomingGroupCall_thenIExpectAccepted() {
    val callId = 1L
    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      ringId = callId,
      groupRecipientId = groupRecipientId,
      ringerRecipient = harness.others[1],
      dateReceived = System.currentTimeMillis(),
      ringState = CallManager.RingUpdate.DECLINED_ON_ANOTHER_DEVICE
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.DECLINED, call?.event)

    GenZappDatabase.calls.acceptIncomingGroupCall(
      call!!
    )

    val acceptedCall = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertEquals(CallTable.Event.ACCEPTED, acceptedCall?.event)
  }

  @Test
  fun givenAGenericGroupCall_whenIAcceptedIncomingGroupCall_thenIExpectAccepted() {
    val era = "aaa"
    val callId = CallId.fromEra(era).longValue()
    GenZappDatabase.calls.insertOrUpdateGroupCallFromLocalEvent(
      groupRecipientId = groupRecipientId,
      sender = harness.others[1],
      timestamp = System.currentTimeMillis(),
      peekGroupCallEraId = "aaa",
      peekJoinedUuids = emptyList(),
      isCallFull = false
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.GENERIC_GROUP_CALL, call?.event)

    GenZappDatabase.calls.acceptIncomingGroupCall(
      call!!
    )

    val acceptedCall = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertEquals(CallTable.Event.JOINED, acceptedCall?.event)
  }

  @Test
  fun givenAnOutgoingRingCall_whenIAcceptedOutgoingGroupCall_thenIExpectOutgoingRing() {
    val callId = 1L
    GenZappDatabase.calls.insertAcceptedGroupCall(
      callId = callId,
      recipientId = groupRecipientId,
      direction = CallTable.Direction.OUTGOING,
      timestamp = 1
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.OUTGOING_RING, call?.event)

    GenZappDatabase.calls.acceptOutgoingGroupCall(
      call!!
    )

    val acceptedCall = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertEquals(CallTable.Event.OUTGOING_RING, acceptedCall?.event)
  }

  @Test
  fun givenARingingCall_whenIAcceptedOutgoingGroupCall_thenIExpectAccepted() {
    val callId = 1L
    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      ringId = callId,
      groupRecipientId = groupRecipientId,
      ringerRecipient = harness.others[1],
      dateReceived = System.currentTimeMillis(),
      ringState = CallManager.RingUpdate.REQUESTED
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.RINGING, call?.event)

    GenZappDatabase.calls.acceptOutgoingGroupCall(
      call!!
    )

    val acceptedCall = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertEquals(CallTable.Event.ACCEPTED, acceptedCall?.event)
  }

  @Test
  fun givenAMissedCall_whenIAcceptedOutgoingGroupCall_thenIExpectAccepted() {
    val callId = 1L
    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      ringId = callId,
      groupRecipientId = groupRecipientId,
      ringerRecipient = harness.others[1],
      dateReceived = System.currentTimeMillis(),
      ringState = CallManager.RingUpdate.EXPIRED_REQUEST
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.MISSED, call?.event)

    GenZappDatabase.calls.acceptOutgoingGroupCall(
      call!!
    )

    val acceptedCall = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertEquals(CallTable.Event.ACCEPTED, acceptedCall?.event)
  }

  @Test
  fun givenADeclinedCall_whenIAcceptedOutgoingGroupCall_thenIExpectAccepted() {
    val callId = 1L
    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      ringId = callId,
      groupRecipientId = groupRecipientId,
      ringerRecipient = harness.others[1],
      dateReceived = System.currentTimeMillis(),
      ringState = CallManager.RingUpdate.DECLINED_ON_ANOTHER_DEVICE
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.DECLINED, call?.event)

    GenZappDatabase.calls.acceptOutgoingGroupCall(
      call!!
    )

    val acceptedCall = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertEquals(CallTable.Event.ACCEPTED, acceptedCall?.event)
  }

  @Test
  fun givenAnAcceptedCall_whenIAcceptedOutgoingGroupCall_thenIExpectAccepted() {
    val callId = 1L
    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      ringId = callId,
      groupRecipientId = groupRecipientId,
      ringerRecipient = harness.others[1],
      dateReceived = System.currentTimeMillis(),
      ringState = CallManager.RingUpdate.REQUESTED
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.RINGING, call?.event)

    GenZappDatabase.calls.acceptIncomingGroupCall(
      call!!
    )

    GenZappDatabase.calls.acceptOutgoingGroupCall(
      GenZappDatabase.calls.getCallById(callId, groupRecipientId)!!
    )

    val acceptedCall = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertEquals(CallTable.Event.ACCEPTED, acceptedCall?.event)
  }

  @Test
  fun givenAGenericGroupCall_whenIAcceptedOutgoingGroupCall_thenIExpectOutgoingRing() {
    val era = "aaa"
    val callId = CallId.fromEra(era).longValue()
    GenZappDatabase.calls.insertOrUpdateGroupCallFromLocalEvent(
      groupRecipientId = groupRecipientId,
      sender = harness.others[1],
      timestamp = System.currentTimeMillis(),
      peekGroupCallEraId = "aaa",
      peekJoinedUuids = emptyList(),
      isCallFull = false
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.GENERIC_GROUP_CALL, call?.event)

    GenZappDatabase.calls.acceptOutgoingGroupCall(
      call!!
    )

    val acceptedCall = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertEquals(CallTable.Event.OUTGOING_RING, acceptedCall?.event)
  }

  @Test
  fun givenAJoinedGroupCall_whenIAcceptedOutgoingGroupCall_thenIExpectOutgoingRing() {
    val era = "aaa"
    val callId = CallId.fromEra(era).longValue()
    GenZappDatabase.calls.insertOrUpdateGroupCallFromLocalEvent(
      groupRecipientId = groupRecipientId,
      sender = harness.others[1],
      timestamp = System.currentTimeMillis(),
      peekGroupCallEraId = "aaa",
      peekJoinedUuids = emptyList(),
      isCallFull = false
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.GENERIC_GROUP_CALL, call?.event)

    GenZappDatabase.calls.acceptIncomingGroupCall(
      call!!
    )

    GenZappDatabase.calls.acceptOutgoingGroupCall(GenZappDatabase.calls.getCallById(callId, groupRecipientId)!!)
    val acceptedCall = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertEquals(CallTable.Event.OUTGOING_RING, acceptedCall?.event)
  }

  @Test
  fun givenNoPriorCallEvent_whenIReceiveAGroupCallUpdateMessage_thenIExpectAGenericGroupCall() {
    val era = "aaa"
    val callId = CallId.fromEra(era).longValue()
    GenZappDatabase.calls.insertOrUpdateGroupCallFromLocalEvent(
      groupRecipientId = groupRecipientId,
      sender = harness.others[1],
      timestamp = System.currentTimeMillis(),
      peekGroupCallEraId = "aaa",
      peekJoinedUuids = emptyList(),
      isCallFull = false
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.GENERIC_GROUP_CALL, call?.event)
  }

  @Test
  fun givenAPriorCallEventWithNewerTimestamp_whenIReceiveAGroupCallUpdateMessage_thenIExpectAnUpdatedTimestamp() {
    val era = "aaa"
    val callId = CallId.fromEra(era).longValue()
    val now = System.currentTimeMillis()
    GenZappDatabase.calls.insertOrUpdateGroupCallFromLocalEvent(
      groupRecipientId = groupRecipientId,
      sender = harness.others[1],
      timestamp = now,
      peekGroupCallEraId = "aaa",
      peekJoinedUuids = emptyList(),
      isCallFull = false
    )

    GenZappDatabase.calls.getCallById(callId, groupRecipientId).let {
      assertNotNull(it)
      assertEquals(now, it?.timestamp)
    }

    GenZappDatabase.calls.insertOrUpdateGroupCallFromLocalEvent(
      groupRecipientId = groupRecipientId,
      sender = harness.others[1],
      timestamp = 1L,
      peekGroupCallEraId = "aaa",
      peekJoinedUuids = emptyList(),
      isCallFull = false
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.GENERIC_GROUP_CALL, call?.event)
    assertEquals(1L, call?.timestamp)
  }

  @Test
  fun givenADeletedCallEvent_whenIReceiveARingUpdate_thenIIgnoreTheRingUpdate() {
    val callId = 1L
    GenZappDatabase.calls.insertDeletedCallFromSyncEvent(
      callId = callId,
      recipientId = groupRecipientId,
      direction = CallTable.Direction.INCOMING,
      timestamp = System.currentTimeMillis(),
      type = CallTable.Type.GROUP_CALL
    )

    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      ringId = callId,
      groupRecipientId = groupRecipientId,
      ringerRecipient = harness.others[1],
      dateReceived = System.currentTimeMillis(),
      ringState = CallManager.RingUpdate.REQUESTED
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.DELETE, call?.event)
  }

  @Test
  fun givenAGenericCallEvent_whenRingRequested_thenISetRingerAndMoveToRingingState() {
    val era = "aaa"
    val callId = CallId.fromEra(era).longValue()
    val now = System.currentTimeMillis()
    GenZappDatabase.calls.insertOrUpdateGroupCallFromLocalEvent(
      groupRecipientId = groupRecipientId,
      sender = harness.others[1],
      timestamp = now,
      peekGroupCallEraId = "aaa",
      peekJoinedUuids = emptyList(),
      isCallFull = false
    )

    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.REQUESTED
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.RINGING, call?.event)
    assertEquals(harness.others[1], call?.ringerRecipient)
  }

  @Test
  fun givenAJoinedCallEvent_whenRingRequested_thenISetRingerAndMoveToRingingState() {
    val era = "aaa"
    val callId = CallId.fromEra(era).longValue()
    val now = System.currentTimeMillis()
    GenZappDatabase.calls.insertAcceptedGroupCall(
      callId,
      groupRecipientId,
      CallTable.Direction.INCOMING,
      now
    )

    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.REQUESTED
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.ACCEPTED, call?.event)
    assertEquals(harness.others[1], call?.ringerRecipient)
  }

  @Test
  fun givenAGenericCallEvent_whenRingExpired_thenISetRingerAndMoveToMissedState() {
    val era = "aaa"
    val callId = CallId.fromEra(era).longValue()
    val now = System.currentTimeMillis()
    GenZappDatabase.calls.insertOrUpdateGroupCallFromLocalEvent(
      groupRecipientId = groupRecipientId,
      sender = harness.others[1],
      timestamp = now,
      peekGroupCallEraId = "aaa",
      peekJoinedUuids = emptyList(),
      isCallFull = false
    )

    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.EXPIRED_REQUEST
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.MISSED, call?.event)
    assertEquals(harness.others[1], call?.ringerRecipient)
  }

  @Test
  fun givenARingingCallEvent_whenRingExpired_thenISetRingerAndMoveToMissedState() {
    val era = "aaa"
    val callId = CallId.fromEra(era).longValue()
    val now = System.currentTimeMillis()
    GenZappDatabase.calls.insertOrUpdateGroupCallFromLocalEvent(
      groupRecipientId = groupRecipientId,
      sender = harness.others[1],
      timestamp = now,
      peekGroupCallEraId = "aaa",
      peekJoinedUuids = emptyList(),
      isCallFull = false
    )

    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.REQUESTED
    )

    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.EXPIRED_REQUEST
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.MISSED, call?.event)
    assertEquals(harness.others[1], call?.ringerRecipient)
  }

  @Test
  fun givenAJoinedCallEvent_whenRingIsCancelledBecauseUserIsBusyLocally_thenIMoveToAcceptedState() {
    val era = "aaa"
    val callId = CallId.fromEra(era).longValue()
    val now = System.currentTimeMillis()
    GenZappDatabase.calls.insertAcceptedGroupCall(
      callId,
      groupRecipientId,
      CallTable.Direction.INCOMING,
      now
    )

    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.BUSY_LOCALLY
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.ACCEPTED, call?.event)
  }

  @Test
  fun givenAJoinedCallEvent_whenRingIsCancelledBecauseUserIsBusyOnAnotherDevice_thenIMoveToAcceptedState() {
    val era = "aaa"
    val callId = CallId.fromEra(era).longValue()
    val now = System.currentTimeMillis()
    GenZappDatabase.calls.insertAcceptedGroupCall(
      callId,
      groupRecipientId,
      CallTable.Direction.INCOMING,
      now
    )

    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.BUSY_ON_ANOTHER_DEVICE
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.ACCEPTED, call?.event)
  }

  @Test
  fun givenARingingCallEvent_whenRingCancelledBecauseUserIsBusyLocally_thenIMoveToMissedState() {
    val era = "aaa"
    val callId = CallId.fromEra(era).longValue()
    val now = System.currentTimeMillis()
    GenZappDatabase.calls.insertOrUpdateGroupCallFromLocalEvent(
      groupRecipientId = groupRecipientId,
      sender = harness.others[1],
      timestamp = now,
      peekGroupCallEraId = "aaa",
      peekJoinedUuids = emptyList(),
      isCallFull = false
    )

    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.REQUESTED
    )

    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.BUSY_LOCALLY
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.MISSED, call?.event)
  }

  @Test
  fun givenARingingCallEvent_whenRingCancelledBecauseUserIsBusyOnAnotherDevice_thenIMoveToMissedState() {
    val era = "aaa"
    val callId = CallId.fromEra(era).longValue()
    val now = System.currentTimeMillis()
    GenZappDatabase.calls.insertOrUpdateGroupCallFromLocalEvent(
      groupRecipientId = groupRecipientId,
      sender = harness.others[1],
      timestamp = now,
      peekGroupCallEraId = "aaa",
      peekJoinedUuids = emptyList(),
      isCallFull = false
    )

    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.REQUESTED
    )

    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.BUSY_ON_ANOTHER_DEVICE
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.MISSED, call?.event)
  }

  @Test
  fun givenACallEvent_whenRingIsAcceptedOnAnotherDevice_thenIMoveToAcceptedState() {
    val era = "aaa"
    val callId = CallId.fromEra(era).longValue()
    val now = System.currentTimeMillis()
    GenZappDatabase.calls.insertOrUpdateGroupCallFromLocalEvent(
      groupRecipientId = groupRecipientId,
      sender = harness.others[1],
      timestamp = now,
      peekGroupCallEraId = "aaa",
      peekJoinedUuids = emptyList(),
      isCallFull = false
    )

    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.ACCEPTED_ON_ANOTHER_DEVICE
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.ACCEPTED, call?.event)
  }

  @Test
  fun givenARingingCallEvent_whenRingDeclinedOnAnotherDevice_thenIMoveToDeclinedState() {
    val era = "aaa"
    val callId = CallId.fromEra(era).longValue()

    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.REQUESTED
    )

    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.DECLINED_ON_ANOTHER_DEVICE
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.DECLINED, call?.event)
  }

  @Test
  fun givenAMissedCallEvent_whenRingDeclinedOnAnotherDevice_thenIMoveToDeclinedState() {
    val era = "aaa"
    val callId = CallId.fromEra(era).longValue()

    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.EXPIRED_REQUEST
    )

    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.DECLINED_ON_ANOTHER_DEVICE
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.DECLINED, call?.event)
  }

  @Test
  fun givenAnOutgoingRingCallEvent_whenRingDeclinedOnAnotherDevice_thenIDoNotChangeState() {
    val era = "aaa"
    val callId = CallId.fromEra(era).longValue()

    GenZappDatabase.calls.insertAcceptedGroupCall(
      callId,
      groupRecipientId,
      CallTable.Direction.OUTGOING,
      System.currentTimeMillis()
    )

    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.DECLINED_ON_ANOTHER_DEVICE
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.OUTGOING_RING, call?.event)
  }

  @Test
  fun givenNoPriorEvent_whenRingRequested_thenICreateAnEventInTheRingingStateAndSetRinger() {
    val callId = 1L
    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.REQUESTED
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.RINGING, call?.event)
    assertEquals(harness.others[1], call?.ringerRecipient)
    assertNotNull(call?.messageId)
  }

  @Test
  fun givenNoPriorEvent_whenRingExpired_thenICreateAnEventInTheMissedStateAndSetRinger() {
    val callId = 1L
    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.EXPIRED_REQUEST
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.MISSED, call?.event)
    assertEquals(harness.others[1], call?.ringerRecipient)
    assertNotNull(call?.messageId)
  }

  @Test
  fun givenNoPriorEvent_whenRingCancelledByRinger_thenICreateAnEventInTheMissedStateAndSetRinger() {
    val callId = 1L
    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.CANCELLED_BY_RINGER
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.MISSED, call?.event)
    assertEquals(harness.others[1], call?.ringerRecipient)
    assertNotNull(call?.messageId)
  }

  @Test
  fun givenNoPriorEvent_whenRingCancelledBecauseUserIsBusyLocally_thenICreateAnEventInTheMissedState() {
    val callId = 1L
    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.BUSY_LOCALLY
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.MISSED, call?.event)
    assertNotNull(call?.messageId)
  }

  @Test
  fun givenNoPriorEvent_whenRingCancelledBecauseUserIsBusyOnAnotherDevice_thenICreateAnEventInTheMissedState() {
    val callId = 1L
    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.BUSY_ON_ANOTHER_DEVICE
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.MISSED, call?.event)
    assertNotNull(call?.messageId)
  }

  @Test
  fun givenNoPriorEvent_whenRingAcceptedOnAnotherDevice_thenICreateAnEventInTheAcceptedState() {
    val callId = 1L
    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.ACCEPTED_ON_ANOTHER_DEVICE
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.ACCEPTED, call?.event)
    assertNotNull(call?.messageId)
  }

  @Test
  fun givenNoPriorEvent_whenRingDeclinedOnAnotherDevice_thenICreateAnEventInTheDeclinedState() {
    val callId = 1L
    GenZappDatabase.calls.insertOrUpdateGroupCallFromRingState(
      callId,
      groupRecipientId,
      harness.others[1],
      System.currentTimeMillis(),
      CallManager.RingUpdate.DECLINED_ON_ANOTHER_DEVICE
    )

    val call = GenZappDatabase.calls.getCallById(callId, groupRecipientId)
    assertNotNull(call)
    assertEquals(CallTable.Event.DECLINED, call?.event)
    assertNotNull(call?.messageId)
  }

  @Test
  fun givenTwoCalls_whenIDeleteBeforeCallB_thenOnlyDeleteCallA() {
    insertTwoCallEvents()

    GenZappDatabase.calls.deleteNonAdHocCallEventsOnOrBefore(1500)

    val allCallEvents = GenZappDatabase.calls.getCalls(0, 2, null, CallLogFilter.ALL)
    assertEquals(1, allCallEvents.size)
    assertEquals(2, allCallEvents.first().record.callId)
  }

  @Test
  fun givenTwoCalls_whenIDeleteBeforeCallA_thenIDoNotDeleteAnyCalls() {
    insertTwoCallEvents()

    GenZappDatabase.calls.deleteNonAdHocCallEventsOnOrBefore(500)

    val allCallEvents = GenZappDatabase.calls.getCalls(0, 2, null, CallLogFilter.ALL)
    assertEquals(2, allCallEvents.size)
    assertEquals(2, allCallEvents[0].record.callId)
    assertEquals(1, allCallEvents[1].record.callId)
  }

  @Test
  fun givenTwoCalls_whenIDeleteOnCallA_thenIOnlyDeleteCallA() {
    insertTwoCallEvents()

    GenZappDatabase.calls.deleteNonAdHocCallEventsOnOrBefore(1000)

    val allCallEvents = GenZappDatabase.calls.getCalls(0, 2, null, CallLogFilter.ALL)
    assertEquals(1, allCallEvents.size)
    assertEquals(2, allCallEvents.first().record.callId)
  }

  @Test
  fun givenTwoCalls_whenIDeleteOnCallB_thenIDeleteBothCalls() {
    insertTwoCallEvents()

    GenZappDatabase.calls.deleteNonAdHocCallEventsOnOrBefore(2000)

    val allCallEvents = GenZappDatabase.calls.getCalls(0, 2, null, CallLogFilter.ALL)
    assertEquals(0, allCallEvents.size)
  }

  @Test
  fun givenTwoCalls_whenIDeleteAfterCallB_thenIDeleteBothCalls() {
    insertTwoCallEvents()

    GenZappDatabase.calls.deleteNonAdHocCallEventsOnOrBefore(2500)

    val allCallEvents = GenZappDatabase.calls.getCalls(0, 2, null, CallLogFilter.ALL)
    assertEquals(0, allCallEvents.size)
  }

  private fun insertTwoCallEvents() {
    GenZappDatabase.calls.insertAcceptedGroupCall(
      1,
      groupRecipientId,
      CallTable.Direction.INCOMING,
      1000
    )

    GenZappDatabase.calls.insertAcceptedGroupCall(
      2,
      groupRecipientId,
      CallTable.Direction.OUTGOING,
      2000
    )
  }
}
