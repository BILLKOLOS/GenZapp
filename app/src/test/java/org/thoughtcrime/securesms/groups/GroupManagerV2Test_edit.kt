@file:Suppress("ClassName")

package org.thoughtcrime.securesms.groups

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers
import org.hamcrest.Matchers.`is`
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.GenZapp.core.util.Hex
import org.GenZapp.core.util.ThreadUtil
import org.GenZapp.core.util.logging.Log
import org.GenZapp.libGenZapp.protocol.logging.GenZappProtocolLogger
import org.GenZapp.libGenZapp.protocol.logging.GenZappProtocolLoggerProvider
import org.GenZapp.libGenZapp.zkgroup.groups.GroupMasterKey
import org.GenZapp.libGenZapp.zkgroup.groups.GroupSecretParams
import org.GenZapp.storageservice.protos.groups.GroupChangeResponse
import org.GenZapp.storageservice.protos.groups.Member
import org.GenZapp.storageservice.protos.groups.local.DecryptedGroup
import org.GenZapp.storageservice.protos.groups.local.DecryptedMember
import org.thoughtcrime.securesms.GenZappStoreRule
import org.thoughtcrime.securesms.TestZkGroupServer
import org.thoughtcrime.securesms.database.GroupStateTestData
import org.thoughtcrime.securesms.database.GroupTable
import org.thoughtcrime.securesms.database.model.databaseprotos.member
import org.thoughtcrime.securesms.groups.v2.GroupCandidateHelper
import org.thoughtcrime.securesms.logging.CustomGenZappProtocolLogger
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testutil.SystemOutLogger
import org.whispersystems.GenZappservice.api.groupsv2.ClientZkOperations
import org.whispersystems.GenZappservice.api.groupsv2.GroupsV2Api
import org.whispersystems.GenZappservice.api.groupsv2.GroupsV2Operations
import org.whispersystems.GenZappservice.api.push.ServiceId.ACI
import org.whispersystems.GenZappservice.api.push.ServiceId.PNI
import org.whispersystems.GenZappservice.api.push.ServiceIds
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class GroupManagerV2Test_edit {

  companion object {
    val server: TestZkGroupServer = TestZkGroupServer()
    val masterKey: GroupMasterKey = GroupMasterKey(Hex.fromStringCondensed("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"))
    val groupSecretParams: GroupSecretParams = GroupSecretParams.deriveFromMasterKey(masterKey)
    val groupId: GroupId.V2 = GroupId.v2(masterKey)

    val selfAci: ACI = ACI.from(UUID.randomUUID())
    val selfPni: PNI = PNI.from(UUID.randomUUID())
    val serviceIds: ServiceIds = ServiceIds(selfAci, selfPni)
    val otherAci: ACI = ACI.from(UUID.randomUUID())
    val selfAndOthers: List<DecryptedMember> = listOf(member(selfAci), member(otherAci))
    val others: List<DecryptedMember> = listOf(member(otherAci))
  }

  private lateinit var groupTable: GroupTable
  private lateinit var groupsV2API: GroupsV2Api
  private lateinit var groupsV2Operations: GroupsV2Operations
  private lateinit var groupsV2Authorization: GroupsV2Authorization
  private lateinit var groupCandidateHelper: GroupCandidateHelper
  private lateinit var sendGroupUpdateHelper: GroupManagerV2.SendGroupUpdateHelper
  private lateinit var groupOperations: GroupsV2Operations.GroupOperations

  private lateinit var manager: GroupManagerV2

  @get:Rule
  val GenZappStore: GenZappStoreRule = GenZappStoreRule()

  @Suppress("UsePropertyAccessSyntax")
  @Before
  fun setUp() {
    ThreadUtil.enforceAssertions = false
    Log.initialize(SystemOutLogger())
    GenZappProtocolLoggerProvider.setProvider(CustomGenZappProtocolLogger())
    GenZappProtocolLoggerProvider.initializeLogging(GenZappProtocolLogger.INFO)

    val clientZkOperations = ClientZkOperations(server.getServerPublicParams())

    groupTable = mockk()
    groupsV2API = mockk()
    groupsV2Operations = GroupsV2Operations(clientZkOperations, 1000)
    groupsV2Authorization = mockk(relaxed = true)
    groupCandidateHelper = mockk()
    sendGroupUpdateHelper = mockk()
    groupOperations = groupsV2Operations.forGroup(groupSecretParams)

    manager = GroupManagerV2(
      ApplicationProvider.getApplicationContext(),
      groupTable,
      groupsV2API,
      groupsV2Operations,
      groupsV2Authorization,
      serviceIds,
      groupCandidateHelper,
      sendGroupUpdateHelper
    )
  }

  private fun given(init: GroupStateTestData.() -> Unit) {
    val data = GroupStateTestData(masterKey, groupOperations)
    data.init()

    every { groupTable.getGroup(groupId) } returns data.groupRecord
    every { groupTable.requireGroup(groupId) } returns data.groupRecord.get()
    every { groupTable.update(any<GroupId.V2>(), any(), any()) } returns Unit
    every { sendGroupUpdateHelper.sendGroupUpdate(masterKey, any(), any(), any()) } returns GroupManagerV2.RecipientAndThread(Recipient.UNKNOWN, 1)
    every { groupsV2API.patchGroup(any(), any(), any()) } returns GroupChangeResponse(groupChange = data.groupChange!!)
  }

  private fun editGroup(perform: GroupManagerV2.GroupEditor.() -> Unit) {
    manager.edit(groupId).use { it.perform() }
  }

  private fun then(then: (DecryptedGroup) -> Unit) {
    val decryptedGroupArg = slot<DecryptedGroup>()
    verify { groupTable.update(groupId, capture(decryptedGroupArg), any()) }
    then(decryptedGroupArg.captured)
  }

  @Test
  fun `when you are the only admin, and then leave the group, server upgrades all other members to administrators and lets you leave`() {
    given {
      localState(
        revision = 5,
        members = listOf(
          member(selfAci, role = Member.Role.ADMINISTRATOR),
          member(otherAci)
        )
      )
      groupChange(6) {
        source(selfAci)
        deleteMember(selfAci)
        modifyRole(otherAci, Member.Role.ADMINISTRATOR)
      }
    }

    editGroup {
      leaveGroup(true)
    }

    then { patchedGroup ->
      assertThat("Revision updated by one", patchedGroup.revision, `is`(6))
      assertThat("Self is no longer in the group", patchedGroup.members.find { it.aciBytes == selfAci.toByteString() }, Matchers.nullValue())
      assertThat("Other is now an admin in the group", patchedGroup.members.find { it.aciBytes == otherAci.toByteString() }?.role, `is`(Member.Role.ADMINISTRATOR))
    }
  }
}
