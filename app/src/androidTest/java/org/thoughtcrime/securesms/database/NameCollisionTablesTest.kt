/*
 * Copyright 2024 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.GenZapp.storageservice.protos.groups.Member
import org.GenZapp.storageservice.protos.groups.local.DecryptedMember
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.profiles.ProfileName
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.testing.GroupTestingUtils
import org.thoughtcrime.securesms.testing.GenZappActivityRule
import org.thoughtcrime.securesms.testing.assertIsSize

@RunWith(AndroidJUnit4::class)
class NameCollisionTablesTest {

  @get:Rule
  val harness = GenZappActivityRule(createGroup = true)

  private lateinit var alice: RecipientId
  private lateinit var bob: RecipientId
  private lateinit var charlie: RecipientId

  @Before
  fun setUp() {
    alice = setUpRecipient(harness.others[0])
    bob = setUpRecipient(harness.others[1])
    charlie = setUpRecipient(harness.others[2])
  }

  @Test
  fun givenAUserWithAThreadIdButNoConflicts_whenIGetCollisionsForThreadRecipient_thenIExpectNoCollisions() {
    val threadRecipientId = alice
    GenZappDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(threadRecipientId))
    val actual = GenZappDatabase.nameCollisions.getCollisionsForThreadRecipientId(threadRecipientId)

    actual assertIsSize 0
  }

  @Test
  fun givenTwoUsers_whenOneChangesTheirProfileNameToMatchTheOther_thenIExpectANameCollision() {
    setProfileName(alice, ProfileName.fromParts("Alice", "Android"))
    setProfileName(bob, ProfileName.fromParts("Bob", "Android"))
    setProfileName(alice, ProfileName.fromParts("Bob", "Android"))

    val actualAlice = GenZappDatabase.nameCollisions.getCollisionsForThreadRecipientId(alice)
    val actualBob = GenZappDatabase.nameCollisions.getCollisionsForThreadRecipientId(bob)

    actualAlice assertIsSize 2
    actualBob assertIsSize 2
  }

  @Test
  fun givenTwoUsersWithANameCollisions_whenOneChangesToADifferentName_thenIExpectNoNameCollisions() {
    setProfileName(alice, ProfileName.fromParts("Bob", "Android"))
    setProfileName(bob, ProfileName.fromParts("Bob", "Android"))
    setProfileName(alice, ProfileName.fromParts("Alice", "Android"))

    val actualAlice = GenZappDatabase.nameCollisions.getCollisionsForThreadRecipientId(alice)
    val actualBob = GenZappDatabase.nameCollisions.getCollisionsForThreadRecipientId(bob)

    actualAlice assertIsSize 0
    actualBob assertIsSize 0
  }

  @Test
  fun givenThreeUsersWithANameCollisions_whenOneChangesToADifferentName_thenIExpectTwoNameCollisions() {
    setProfileName(alice, ProfileName.fromParts("Bob", "Android"))
    setProfileName(bob, ProfileName.fromParts("Bob", "Android"))
    setProfileName(charlie, ProfileName.fromParts("Bob", "Android"))
    setProfileName(alice, ProfileName.fromParts("Alice", "Android"))

    val actualAlice = GenZappDatabase.nameCollisions.getCollisionsForThreadRecipientId(alice)
    val actualBob = GenZappDatabase.nameCollisions.getCollisionsForThreadRecipientId(bob)
    val actualCharlie = GenZappDatabase.nameCollisions.getCollisionsForThreadRecipientId(charlie)

    actualAlice assertIsSize 0
    actualBob assertIsSize 2
    actualCharlie assertIsSize 2
  }

  @Test
  fun givenTwoUsersWithADismissedNameCollision_whenOneChangesToADifferentNameAndBack_thenIExpectANameCollision() {
    setProfileName(alice, ProfileName.fromParts("Bob", "Android"))
    setProfileName(bob, ProfileName.fromParts("Bob", "Android"))
    GenZappDatabase.nameCollisions.markCollisionsForThreadRecipientDismissed(alice)

    setProfileName(alice, ProfileName.fromParts("Alice", "Android"))
    setProfileName(alice, ProfileName.fromParts("Bob", "Android"))

    val actualAlice = GenZappDatabase.nameCollisions.getCollisionsForThreadRecipientId(alice)

    actualAlice assertIsSize 2
  }

  @Test
  fun givenADismissedNameCollisionForAlice_whenIGetNameCollisionsForAlice_thenIExpectNoNameCollisions() {
    setProfileName(alice, ProfileName.fromParts("Bob", "Android"))
    setProfileName(bob, ProfileName.fromParts("Bob", "Android"))
    GenZappDatabase.nameCollisions.markCollisionsForThreadRecipientDismissed(alice)

    val actualCollisions = GenZappDatabase.nameCollisions.getCollisionsForThreadRecipientId(alice)

    actualCollisions assertIsSize 0
  }

  @Test
  fun givenADismissedNameCollisionForAliceThatIUpdate_whenIGetNameCollisionsForAlice_thenIExpectNoNameCollisions() {
    GenZappDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(alice))

    setProfileName(alice, ProfileName.fromParts("Bob", "Android"))
    setProfileName(bob, ProfileName.fromParts("Bob", "Android"))
    GenZappDatabase.nameCollisions.markCollisionsForThreadRecipientDismissed(alice)
    setProfileName(bob, ProfileName.fromParts("Bob", "Android"))

    val actualCollisions = GenZappDatabase.nameCollisions.getCollisionsForThreadRecipientId(alice)

    actualCollisions assertIsSize 0
  }

  @Test
  fun givenADismissedNameCollisionForAlice_whenIGetNameCollisionsForBob_thenIExpectANameCollisionWithTwoEntries() {
    GenZappDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(alice))

    setProfileName(alice, ProfileName.fromParts("Bob", "Android"))
    setProfileName(bob, ProfileName.fromParts("Bob", "Android"))
    GenZappDatabase.nameCollisions.markCollisionsForThreadRecipientDismissed(alice)

    val actualCollisions = GenZappDatabase.nameCollisions.getCollisionsForThreadRecipientId(bob)

    actualCollisions assertIsSize 2
  }

  @Test
  fun givenAGroupWithAliceAndBob_whenIInsertNameChangeMessageForAlice_thenIExpectAGroupNameCollision() {
    val alice = Recipient.resolved(alice)
    val bob = Recipient.resolved(bob)
    val info = createGroup()

    setProfileName(alice.id, ProfileName.fromParts("Bob", "Android"))
    setProfileName(bob.id, ProfileName.fromParts("Bob", "Android"))

    GenZappDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(info.recipientId))
    GenZappDatabase.messages.insertProfileNameChangeMessages(alice, "Bob Android", "Alice Android")

    val collisions = GenZappDatabase.nameCollisions.getCollisionsForThreadRecipientId(info.recipientId)

    collisions assertIsSize 2
  }

  @Test
  fun givenAGroupWithAliceAndBobWithDismissedCollision_whenIInsertNameChangeMessageForAlice_thenIExpectAGroupNameCollision() {
    val alice = Recipient.resolved(alice)
    val bob = Recipient.resolved(bob)
    val info = createGroup()

    setProfileName(alice.id, ProfileName.fromParts("Bob", "Android"))
    setProfileName(bob.id, ProfileName.fromParts("Bob", "Android"))

    GenZappDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(info.recipientId))
    GenZappDatabase.messages.insertProfileNameChangeMessages(alice, "Bob Android", "Alice Android")
    GenZappDatabase.nameCollisions.markCollisionsForThreadRecipientDismissed(info.recipientId)
    GenZappDatabase.messages.insertProfileNameChangeMessages(alice, "Bob Android", "Alice Android")

    val collisions = GenZappDatabase.nameCollisions.getCollisionsForThreadRecipientId(info.recipientId)

    collisions assertIsSize 0
  }

  @Test
  fun givenAGroupWithAliceAndBob_whenIInsertNameChangeMessageForAliceWithMismatch_thenIExpectNoGroupNameCollision() {
    val alice = Recipient.resolved(alice)
    val bob = Recipient.resolved(bob)
    val info = createGroup()

    setProfileName(alice.id, ProfileName.fromParts("Alice", "Android"))
    setProfileName(bob.id, ProfileName.fromParts("Bob", "Android"))

    GenZappDatabase.threads.getOrCreateThreadIdFor(Recipient.resolved(info.recipientId))
    GenZappDatabase.messages.insertProfileNameChangeMessages(alice, "Alice Android", "Bob Android")

    val collisions = GenZappDatabase.nameCollisions.getCollisionsForThreadRecipientId(info.recipientId)

    collisions assertIsSize 0
  }

  private fun setUpRecipient(recipientId: RecipientId): RecipientId {
    GenZappDatabase.recipients.setProfileSharing(recipientId, false)
    val threadId = GenZappDatabase.threads.getOrCreateThreadIdFor(recipientId, false)

    MmsHelper.insert(
      threadId = threadId,
      message = IncomingMessage(
        type = MessageType.NORMAL,
        from = recipientId,
        groupId = null,
        body = "hi",
        sentTimeMillis = 100L,
        receivedTimeMillis = 200L,
        serverTimeMillis = 100L,
        isUnidentified = true
      )
    )

    return recipientId
  }

  private fun setProfileName(recipientId: RecipientId, name: ProfileName) {
    GenZappDatabase.recipients.setProfileName(recipientId, name)
    GenZappDatabase.nameCollisions.handleIndividualNameCollision(recipientId)
  }

  private fun createGroup(): GroupTestingUtils.TestGroupInfo {
    return GroupTestingUtils.insertGroup(
      revision = 0,
      DecryptedMember(
        aciBytes = harness.self.requireAci().toByteString(),
        role = Member.Role.ADMINISTRATOR
      ),
      DecryptedMember(
        aciBytes = Recipient.resolved(alice).requireAci().toByteString(),
        role = Member.Role.ADMINISTRATOR
      ),
      DecryptedMember(
        aciBytes = Recipient.resolved(bob).requireAci().toByteString(),
        role = Member.Role.ADMINISTRATOR
      )
    )
  }
}
