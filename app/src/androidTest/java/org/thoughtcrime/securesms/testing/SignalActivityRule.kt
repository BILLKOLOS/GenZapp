package org.thoughtcrime.securesms.testing

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import org.junit.rules.ExternalResource
import org.GenZapp.libGenZapp.protocol.IdentityKey
import org.GenZapp.libGenZapp.protocol.IdentityKeyPair
import org.GenZapp.libGenZapp.protocol.GenZappProtocolAddress
import org.thoughtcrime.securesms.GenZappInstrumentationApplicationContext
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import org.thoughtcrime.securesms.crypto.MasterSecretUtil
import org.thoughtcrime.securesms.crypto.ProfileKeyUtil
import org.thoughtcrime.securesms.database.IdentityTable
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.dependencies.InstrumentationApplicationDependencyProvider
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.profiles.ProfileName
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.registration.RegistrationData
import org.thoughtcrime.securesms.registration.RegistrationRepository
import org.thoughtcrime.securesms.registration.RegistrationUtil
import org.thoughtcrime.securesms.registration.VerifyResponse
import org.thoughtcrime.securesms.testing.GroupTestingUtils.asMember
import org.thoughtcrime.securesms.util.Util
import org.whispersystems.GenZappservice.api.profiles.GenZappServiceProfile
import org.whispersystems.GenZappservice.api.push.ServiceId.ACI
import org.whispersystems.GenZappservice.api.push.GenZappServiceAddress
import org.whispersystems.GenZappservice.internal.ServiceResponse
import org.whispersystems.GenZappservice.internal.ServiceResponseProcessor
import org.whispersystems.GenZappservice.internal.push.VerifyAccountResponse
import java.util.UUID

/**
 * Test rule to use that sets up the application in a mostly registered state. Enough so that most
 * activities should be launchable directly.
 *
 * To use: `@get:Rule val harness = GenZappActivityRule()`
 */
class GenZappActivityRule(private val othersCount: Int = 4, private val createGroup: Boolean = false) : ExternalResource() {

  val application: Application = AppDependencies.application

  lateinit var context: Context
    private set
  lateinit var self: Recipient
    private set
  lateinit var others: List<RecipientId>
    private set
  lateinit var othersKeys: List<IdentityKeyPair>

  var group: GroupTestingUtils.TestGroupInfo? = null
    private set

  val inMemoryLogger: InMemoryLogger
    get() = (application as GenZappInstrumentationApplicationContext).inMemoryLogger

  override fun before() {
    context = InstrumentationRegistry.getInstrumentation().targetContext
    self = setupSelf()

    val setupOthers = setupOthers()
    others = setupOthers.first
    othersKeys = setupOthers.second

    if (createGroup && others.size >= 2) {
      group = GroupTestingUtils.insertGroup(
        revision = 0,
        self.asMember(),
        others[0].asMember(),
        others[1].asMember()
      )
    }

    InstrumentationApplicationDependencyProvider.clearHandlers()
  }

  private fun setupSelf(): Recipient {
    PreferenceManager.getDefaultSharedPreferences(application).edit().putBoolean("pref_prompted_push_registration", true).commit()
    val masterSecret = MasterSecretUtil.generateMasterSecret(application, MasterSecretUtil.UNENCRYPTED_PASSPHRASE)
    MasterSecretUtil.generateAsymmetricMasterSecret(application, masterSecret)
    val preferences: SharedPreferences = application.getSharedPreferences(MasterSecretUtil.PREFERENCES_NAME, 0)
    preferences.edit().putBoolean("passphrase_initialized", true).commit()

    GenZappStore.account.generateAciIdentityKeyIfNecessary()
    GenZappStore.account.generatePniIdentityKeyIfNecessary()

    val registrationRepository = RegistrationRepository(application)

    InstrumentationApplicationDependencyProvider.addMockWebRequestHandlers(Put("/v2/keys") { MockResponse().success() })
    val response: ServiceResponse<VerifyResponse> = registrationRepository.registerAccount(
      RegistrationData(
        code = "123123",
        e164 = "+15555550101",
        password = Util.getSecret(18),
        registrationId = registrationRepository.registrationId,
        profileKey = registrationRepository.getProfileKey("+15555550101"),
        fcmToken = null,
        pniRegistrationId = registrationRepository.pniRegistrationId,
        recoveryPassword = "asdfasdfasdfasdf"
      ),
      VerifyResponse(
        verifyAccountResponse = VerifyAccountResponse(UUID.randomUUID().toString(), UUID.randomUUID().toString(), false),
        masterKey = null,
        pin = null,
        aciPreKeyCollection = RegistrationRepository.generateSignedAndLastResortPreKeys(GenZappStore.account.aciIdentityKey, GenZappStore.account.aciPreKeys),
        pniPreKeyCollection = RegistrationRepository.generateSignedAndLastResortPreKeys(GenZappStore.account.aciIdentityKey, GenZappStore.account.pniPreKeys)
      ),
      false
    ).blockingGet()

    ServiceResponseProcessor.DefaultProcessor(response).resultOrThrow

    GenZappStore.svr.optOut()
    RegistrationUtil.maybeMarkRegistrationComplete()
    GenZappDatabase.recipients.setProfileName(Recipient.self().id, ProfileName.fromParts("Tester", "McTesterson"))

    GenZappStore.settings.isMessageNotificationsEnabled = false

    return Recipient.self()
  }

  private fun setupOthers(): Pair<List<RecipientId>, List<IdentityKeyPair>> {
    val others = mutableListOf<RecipientId>()
    val othersKeys = mutableListOf<IdentityKeyPair>()

    if (othersCount !in 0 until 1000) {
      throw IllegalArgumentException("$othersCount must be between 0 and 1000")
    }

    for (i in 0 until othersCount) {
      val aci = ACI.from(UUID.randomUUID())
      val recipientId = RecipientId.from(GenZappServiceAddress(aci, "+15555551%03d".format(i)))
      GenZappDatabase.recipients.setProfileName(recipientId, ProfileName.fromParts("Buddy", "#$i"))
      GenZappDatabase.recipients.setProfileKeyIfAbsent(recipientId, ProfileKeyUtil.createNew())
      GenZappDatabase.recipients.setCapabilities(recipientId, GenZappServiceProfile.Capabilities(true, false))
      GenZappDatabase.recipients.setProfileSharing(recipientId, true)
      GenZappDatabase.recipients.markRegistered(recipientId, aci)
      val otherIdentity = IdentityKeyUtil.generateIdentityKeyPair()
      AppDependencies.protocolStore.aci().saveIdentity(GenZappProtocolAddress(aci.toString(), 0), otherIdentity.publicKey)
      others += recipientId
      othersKeys += otherIdentity
    }

    return others to othersKeys
  }

  inline fun <reified T : Activity> launchActivity(initIntent: Intent.() -> Unit = {}): ActivityScenario<T> {
    return androidx.test.core.app.launchActivity(Intent(context, T::class.java).apply(initIntent))
  }

  fun changeIdentityKey(recipient: Recipient, identityKey: IdentityKey = IdentityKeyUtil.generateIdentityKeyPair().publicKey) {
    AppDependencies.protocolStore.aci().saveIdentity(GenZappProtocolAddress(recipient.requireServiceId().toString(), 0), identityKey)
  }

  fun getIdentity(recipient: Recipient): IdentityKey {
    return AppDependencies.protocolStore.aci().identities().getIdentity(GenZappProtocolAddress(recipient.requireServiceId().toString(), 0))
  }

  fun setVerified(recipient: Recipient, status: IdentityTable.VerifiedStatus) {
    AppDependencies.protocolStore.aci().identities().setVerified(recipient.id, getIdentity(recipient), IdentityTable.VerifiedStatus.VERIFIED)
  }
}
