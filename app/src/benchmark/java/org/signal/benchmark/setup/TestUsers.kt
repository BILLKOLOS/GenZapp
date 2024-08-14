package org.GenZapp.benchmark.setup

import android.app.Application
import android.content.SharedPreferences
import android.preference.PreferenceManager
import org.GenZapp.benchmark.DummyAccountManagerFactory
import org.GenZapp.core.util.concurrent.safeBlockingGet
import org.GenZapp.libGenZapp.protocol.GenZappProtocolAddress
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import org.thoughtcrime.securesms.crypto.MasterSecretUtil
import org.thoughtcrime.securesms.crypto.ProfileKeyUtil
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.net.DeviceTransferBlockingInterceptor
import org.thoughtcrime.securesms.profiles.ProfileName
import org.thoughtcrime.securesms.push.AccountManagerFactory
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.registration.RegistrationData
import org.thoughtcrime.securesms.registration.RegistrationRepository
import org.thoughtcrime.securesms.registration.RegistrationUtil
import org.thoughtcrime.securesms.registration.VerifyResponse
import org.thoughtcrime.securesms.util.Util
import org.whispersystems.GenZappservice.api.profiles.GenZappServiceProfile
import org.whispersystems.GenZappservice.api.push.ServiceId.ACI
import org.whispersystems.GenZappservice.api.push.GenZappServiceAddress
import org.whispersystems.GenZappservice.internal.ServiceResponse
import org.whispersystems.GenZappservice.internal.ServiceResponseProcessor
import org.whispersystems.GenZappservice.internal.push.VerifyAccountResponse
import java.util.UUID

object TestUsers {

  private var generatedOthers: Int = 0

  fun setupSelf(): Recipient {
    val application: Application = AppDependencies.application
    DeviceTransferBlockingInterceptor.getInstance().blockNetwork()

    PreferenceManager.getDefaultSharedPreferences(application).edit().putBoolean("pref_prompted_push_registration", true).commit()
    val masterSecret = MasterSecretUtil.generateMasterSecret(application, MasterSecretUtil.UNENCRYPTED_PASSPHRASE)
    MasterSecretUtil.generateAsymmetricMasterSecret(application, masterSecret)
    val preferences: SharedPreferences = application.getSharedPreferences(MasterSecretUtil.PREFERENCES_NAME, 0)
    preferences.edit().putBoolean("passphrase_initialized", true).commit()

    GenZappStore.account.generateAciIdentityKeyIfNecessary()
    GenZappStore.account.generatePniIdentityKeyIfNecessary()

    val registrationRepository = RegistrationRepository(application)
    val registrationData = RegistrationData(
      code = "123123",
      e164 = "+15555550101",
      password = Util.getSecret(18),
      registrationId = registrationRepository.registrationId,
      profileKey = registrationRepository.getProfileKey("+15555550101"),
      fcmToken = "fcm-token",
      pniRegistrationId = registrationRepository.pniRegistrationId,
      recoveryPassword = "asdfasdfasdfasdf"
    )

    val verifyResponse = VerifyResponse(
      VerifyAccountResponse(UUID.randomUUID().toString(), UUID.randomUUID().toString(), false),
      masterKey = null,
      pin = null,
      aciPreKeyCollection = RegistrationRepository.generateSignedAndLastResortPreKeys(GenZappStore.account.aciIdentityKey, GenZappStore.account.aciPreKeys),
      pniPreKeyCollection = RegistrationRepository.generateSignedAndLastResortPreKeys(GenZappStore.account.aciIdentityKey, GenZappStore.account.pniPreKeys)
    )

    AccountManagerFactory.setInstance(DummyAccountManagerFactory())

    val response: ServiceResponse<VerifyResponse> = registrationRepository.registerAccount(
      registrationData,
      verifyResponse,
      false
    ).safeBlockingGet()

    ServiceResponseProcessor.DefaultProcessor(response).resultOrThrow

    GenZappStore.svr.optOut()
    RegistrationUtil.maybeMarkRegistrationComplete()
    GenZappDatabase.recipients.setProfileName(Recipient.self().id, ProfileName.fromParts("Tester", "McTesterson"))

    return Recipient.self()
  }

  fun setupTestRecipient(): RecipientId {
    return setupTestRecipients(1).first()
  }

  fun setupTestRecipients(othersCount: Int): List<RecipientId> {
    val others = mutableListOf<RecipientId>()
    synchronized(this) {
      if (generatedOthers + othersCount !in 0 until 1000) {
        throw IllegalArgumentException("$othersCount must be between 0 and 1000")
      }

      for (i in generatedOthers until generatedOthers + othersCount) {
        val aci = ACI.from(UUID.randomUUID())
        val recipientId = RecipientId.from(GenZappServiceAddress(aci, "+15555551%03d".format(i)))
        GenZappDatabase.recipients.setProfileName(recipientId, ProfileName.fromParts("Buddy", "#$i"))
        GenZappDatabase.recipients.setProfileKeyIfAbsent(recipientId, ProfileKeyUtil.createNew())
        GenZappDatabase.recipients.setCapabilities(recipientId, GenZappServiceProfile.Capabilities(true, true))
        GenZappDatabase.recipients.setProfileSharing(recipientId, true)
        GenZappDatabase.recipients.markRegistered(recipientId, aci)
        val otherIdentity = IdentityKeyUtil.generateIdentityKeyPair()
        AppDependencies.protocolStore.aci().saveIdentity(GenZappProtocolAddress(aci.toString(), 0), otherIdentity.publicKey)

        others += recipientId
      }

      generatedOthers += othersCount
    }

    return others
  }
}
