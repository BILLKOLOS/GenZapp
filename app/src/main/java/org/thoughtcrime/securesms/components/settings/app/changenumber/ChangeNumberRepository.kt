/*
 * Copyright 2024 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.changenumber

import androidx.annotation.WorkerThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import org.GenZapp.core.util.logging.Log
import org.GenZapp.libGenZapp.protocol.IdentityKeyPair
import org.GenZapp.libGenZapp.protocol.GenZappProtocolAddress
import org.GenZapp.libGenZapp.protocol.state.KyberPreKeyRecord
import org.GenZapp.libGenZapp.protocol.state.GenZappProtocolStore
import org.GenZapp.libGenZapp.protocol.state.SignedPreKeyRecord
import org.GenZapp.libGenZapp.protocol.util.KeyHelper
import org.GenZapp.libGenZapp.protocol.util.Medium
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import org.thoughtcrime.securesms.crypto.PreKeyUtil
import org.thoughtcrime.securesms.database.IdentityTable
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.database.model.databaseprotos.PendingChangeNumberMetadata
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.RefreshAttributesJob
import org.thoughtcrime.securesms.keyvalue.CertificateType
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.pin.SvrRepository
import org.thoughtcrime.securesms.pin.SvrWrongPinException
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.registration.viewmodel.SvrAuthCredentialSet
import org.thoughtcrime.securesms.storage.StorageSyncHelper
import org.whispersystems.GenZappservice.api.NetworkResult
import org.whispersystems.GenZappservice.api.GenZappServiceAccountManager
import org.whispersystems.GenZappservice.api.GenZappServiceMessageSender
import org.whispersystems.GenZappservice.api.SvrNoDataException
import org.whispersystems.GenZappservice.api.account.ChangePhoneNumberRequest
import org.whispersystems.GenZappservice.api.account.PreKeyUpload
import org.whispersystems.GenZappservice.api.kbs.MasterKey
import org.whispersystems.GenZappservice.api.push.ServiceId
import org.whispersystems.GenZappservice.api.push.ServiceIdType
import org.whispersystems.GenZappservice.api.push.GenZappServiceAddress
import org.whispersystems.GenZappservice.api.push.SignedPreKeyEntity
import org.whispersystems.GenZappservice.internal.push.KyberPreKeyEntity
import org.whispersystems.GenZappservice.internal.push.OutgoingPushMessage
import org.whispersystems.GenZappservice.internal.push.SyncMessage
import org.whispersystems.GenZappservice.internal.push.VerifyAccountResponse
import org.whispersystems.GenZappservice.internal.push.WhoAmIResponse
import org.whispersystems.GenZappservice.internal.push.exceptions.MismatchedDevicesException
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Repository to perform data operations during change number.
 *
 * @see [org.thoughtcrime.securesms.registration.data.RegistrationRepository]
 */
class ChangeNumberRepository(
  private val accountManager: GenZappServiceAccountManager = AppDependencies.GenZappServiceAccountManager,
  private val messageSender: GenZappServiceMessageSender = AppDependencies.GenZappServiceMessageSender
) {

  companion object {
    private val TAG = Log.tag(ChangeNumberRepository::class.java)
  }

  fun whoAmI(): WhoAmIResponse {
    return accountManager.whoAmI
  }

  suspend fun ensureDecryptionsDrained(timeout: Duration = 15.seconds) =
    withTimeoutOrNull(timeout) {
      suspendCancellableCoroutine {
        val drainedListener = object : Runnable {
          override fun run() {
            AppDependencies
              .incomingMessageObserver
              .removeDecryptionDrainedListener(this)
            Log.d(TAG, "Decryptions drained.")
            it.resume(true)
          }
        }

        it.invokeOnCancellation { cancellationCause ->
          AppDependencies
            .incomingMessageObserver
            .removeDecryptionDrainedListener(drainedListener)
          Log.d(TAG, "Decryptions draining canceled.", cancellationCause)
        }

        AppDependencies
          .incomingMessageObserver
          .addDecryptionDrainedListener(drainedListener)
        Log.d(TAG, "Waiting for decryption drain.")
      }
    }

  @WorkerThread
  fun changeLocalNumber(e164: String, pni: ServiceId.PNI) {
    val oldStorageId: ByteArray? = Recipient.self().storageId
    GenZappDatabase.recipients.updateSelfE164(e164, pni)
    val newStorageId: ByteArray? = Recipient.self().storageId

    if (e164 != GenZappStore.account.requireE164() && MessageDigest.isEqual(oldStorageId, newStorageId)) {
      Log.w(TAG, "Self storage id was not rotated, attempting to rotate again")
      GenZappDatabase.recipients.rotateStorageId(Recipient.self().id)
      StorageSyncHelper.scheduleSyncForDataChange()
      val secondAttemptStorageId: ByteArray? = Recipient.self().storageId
      if (MessageDigest.isEqual(oldStorageId, secondAttemptStorageId)) {
        Log.w(TAG, "Second attempt also failed to rotate storage id")
      }
    }

    AppDependencies.recipientCache.clear()

    GenZappStore.account.setE164(e164)
    GenZappStore.account.setPni(pni)
    AppDependencies.resetProtocolStores()

    AppDependencies.groupsV2Authorization.clear()

    val metadata: PendingChangeNumberMetadata? = GenZappStore.misc.pendingChangeNumberMetadata
    if (metadata == null) {
      Log.w(TAG, "No change number metadata, this shouldn't happen")
      throw AssertionError("No change number metadata")
    }

    val originalPni = ServiceId.PNI.parseOrThrow(metadata.previousPni)

    if (originalPni == pni) {
      Log.i(TAG, "No change has occurred, PNI is unchanged: $pni")
    } else {
      val pniIdentityKeyPair = IdentityKeyPair(metadata.pniIdentityKeyPair.toByteArray())
      val pniRegistrationId = metadata.pniRegistrationId
      val pniSignedPreyKeyId = metadata.pniSignedPreKeyId
      val pniLastResortKyberPreKeyId = metadata.pniLastResortKyberPreKeyId

      val pniProtocolStore = AppDependencies.protocolStore.pni()
      val pniMetadataStore = GenZappStore.account.pniPreKeys

      GenZappStore.account.pniRegistrationId = pniRegistrationId
      GenZappStore.account.setPniIdentityKeyAfterChangeNumber(pniIdentityKeyPair)

      val signedPreKey = pniProtocolStore.loadSignedPreKey(pniSignedPreyKeyId)
      val oneTimeEcPreKeys = PreKeyUtil.generateAndStoreOneTimeEcPreKeys(pniProtocolStore, pniMetadataStore)
      val lastResortKyberPreKey = pniProtocolStore.loadLastResortKyberPreKeys().firstOrNull { it.id == pniLastResortKyberPreKeyId }
      val oneTimeKyberPreKeys = PreKeyUtil.generateAndStoreOneTimeKyberPreKeys(pniProtocolStore, pniMetadataStore)

      if (lastResortKyberPreKey == null) {
        Log.w(TAG, "Last-resort kyber prekey is missing!")
      }

      pniMetadataStore.activeSignedPreKeyId = signedPreKey.id
      Log.i(TAG, "Submitting prekeys with PNI identity key: ${pniIdentityKeyPair.publicKey.fingerprint}")

      accountManager.setPreKeys(
        PreKeyUpload(
          serviceIdType = ServiceIdType.PNI,
          signedPreKey = signedPreKey,
          oneTimeEcPreKeys = oneTimeEcPreKeys,
          lastResortKyberPreKey = lastResortKyberPreKey,
          oneTimeKyberPreKeys = oneTimeKyberPreKeys
        )
      )
      pniMetadataStore.isSignedPreKeyRegistered = true
      pniMetadataStore.lastResortKyberPreKeyId = pniLastResortKyberPreKeyId

      pniProtocolStore.identities().saveIdentityWithoutSideEffects(
        Recipient.self().id,
        pni,
        pniProtocolStore.identityKeyPair.publicKey,
        IdentityTable.VerifiedStatus.VERIFIED,
        true,
        System.currentTimeMillis(),
        true
      )

      GenZappStore.misc.hasPniInitializedDevices = true
      AppDependencies.groupsV2Authorization.clear()
    }

    Recipient.self().live().refresh()
    StorageSyncHelper.scheduleSyncForDataChange()

    AppDependencies.resetNetwork()
    AppDependencies.incomingMessageObserver

    AppDependencies.jobManager.add(RefreshAttributesJob())

    rotateCertificates()
  }

  @WorkerThread
  private fun rotateCertificates() {
    val certificateTypes = GenZappStore.phoneNumberPrivacy.allCertificateTypes

    Log.i(TAG, "Rotating these certificates $certificateTypes")

    for (certificateType in certificateTypes) {
      val certificate: ByteArray? = when (certificateType) {
        CertificateType.ACI_AND_E164 -> accountManager.senderCertificate
        CertificateType.ACI_ONLY -> accountManager.senderCertificateForPhoneNumberPrivacy
        else -> throw AssertionError()
      }

      Log.i(TAG, "Successfully got $certificateType certificate")

      GenZappStore.certificate.setUnidentifiedAccessCertificate(certificateType, certificate)
    }
  }

  suspend fun changeNumberWithRecoveryPassword(recoveryPassword: String, newE164: String): ChangeNumberResult {
    return changeNumberInternal(recoveryPassword = recoveryPassword, newE164 = newE164)
  }

  suspend fun changeNumberWithoutRegistrationLock(sessionId: String, newE164: String): ChangeNumberResult {
    return changeNumberInternal(sessionId = sessionId, newE164 = newE164)
  }

  suspend fun changeNumberWithRegistrationLock(
    sessionId: String,
    newE164: String,
    pin: String,
    svrAuthCredentials: SvrAuthCredentialSet
  ): ChangeNumberResult {
    val masterKey: MasterKey

    try {
      masterKey = SvrRepository.restoreMasterKeyPreRegistration(svrAuthCredentials, pin)
    } catch (e: SvrWrongPinException) {
      return ChangeNumberResult.SvrWrongPin(e)
    } catch (e: SvrNoDataException) {
      return ChangeNumberResult.SvrNoData(e)
    } catch (e: IOException) {
      return ChangeNumberResult.UnknownError(e)
    }

    val registrationLock = masterKey.deriveRegistrationLock()
    return changeNumberInternal(sessionId = sessionId, registrationLock = registrationLock, newE164 = newE164)
  }

  /**
   * Sends a request to the service to change the phone number associated with this account.
   */
  private suspend fun changeNumberInternal(sessionId: String? = null, recoveryPassword: String? = null, registrationLock: String? = null, newE164: String): ChangeNumberResult {
    check((sessionId != null && recoveryPassword == null) || (sessionId == null && recoveryPassword != null))
    var completed = false
    var attempts = 0
    lateinit var result: NetworkResult<VerifyAccountResponse>

    while (!completed && attempts < 5) {
      Log.i(TAG, "Attempt #$attempts")
      val (request: ChangePhoneNumberRequest, metadata: PendingChangeNumberMetadata) = createChangeNumberRequest(
        sessionId = sessionId,
        recoveryPassword = recoveryPassword,
        newE164 = newE164,
        registrationLock = registrationLock
      )

      GenZappStore.misc.setPendingChangeNumberMetadata(metadata)
      withContext(Dispatchers.IO) {
        result = accountManager.registrationApi.changeNumber(request)
      }

      val possibleError = result.getCause() as? MismatchedDevicesException
      if (possibleError != null) {
        messageSender.handleChangeNumberMismatchDevices(possibleError.mismatchedDevices)
        attempts++
      } else {
        completed = true
      }
    }
    Log.i(TAG, "Returning change number network result.")
    return ChangeNumberResult.from(
      result.map { accountRegistrationResponse: VerifyAccountResponse ->
        NumberChangeResult(
          uuid = accountRegistrationResponse.uuid,
          pni = accountRegistrationResponse.pni,
          storageCapable = accountRegistrationResponse.storageCapable,
          number = accountRegistrationResponse.number
        )
      }
    )
  }

  @WorkerThread
  private fun createChangeNumberRequest(
    sessionId: String? = null,
    recoveryPassword: String? = null,
    newE164: String,
    registrationLock: String? = null
  ): ChangeNumberRequestData {
    val selfIdentifier: String = GenZappStore.account.requireAci().toString()
    val aciProtocolStore: GenZappProtocolStore = AppDependencies.protocolStore.aci()

    val pniIdentity: IdentityKeyPair = IdentityKeyUtil.generateIdentityKeyPair()
    val deviceMessages = mutableListOf<OutgoingPushMessage>()
    val devicePniSignedPreKeys = mutableMapOf<Int, SignedPreKeyEntity>()
    val devicePniLastResortKyberPreKeys = mutableMapOf<Int, KyberPreKeyEntity>()
    val pniRegistrationIds = mutableMapOf<Int, Int>()
    val primaryDeviceId: Int = GenZappServiceAddress.DEFAULT_DEVICE_ID

    val devices: List<Int> = listOf(primaryDeviceId) + aciProtocolStore.getSubDeviceSessions(selfIdentifier)

    devices
      .filter { it == primaryDeviceId || aciProtocolStore.containsSession(GenZappProtocolAddress(selfIdentifier, it)) }
      .forEach { deviceId ->
        // Signed Prekeys
        val signedPreKeyRecord: SignedPreKeyRecord = if (deviceId == primaryDeviceId) {
          PreKeyUtil.generateAndStoreSignedPreKey(AppDependencies.protocolStore.pni(), GenZappStore.account.pniPreKeys, pniIdentity.privateKey)
        } else {
          PreKeyUtil.generateSignedPreKey(SecureRandom().nextInt(Medium.MAX_VALUE), pniIdentity.privateKey)
        }
        devicePniSignedPreKeys[deviceId] = SignedPreKeyEntity(signedPreKeyRecord.id, signedPreKeyRecord.keyPair.publicKey, signedPreKeyRecord.signature)

        // Last-resort kyber prekeys
        val lastResortKyberPreKeyRecord: KyberPreKeyRecord = if (deviceId == primaryDeviceId) {
          PreKeyUtil.generateAndStoreLastResortKyberPreKey(AppDependencies.protocolStore.pni(), GenZappStore.account.pniPreKeys, pniIdentity.privateKey)
        } else {
          PreKeyUtil.generateLastResortKyberPreKey(SecureRandom().nextInt(Medium.MAX_VALUE), pniIdentity.privateKey)
        }
        devicePniLastResortKyberPreKeys[deviceId] = KyberPreKeyEntity(lastResortKyberPreKeyRecord.id, lastResortKyberPreKeyRecord.keyPair.publicKey, lastResortKyberPreKeyRecord.signature)

        // Registration Ids
        var pniRegistrationId = -1

        while (pniRegistrationId < 0 || pniRegistrationIds.values.contains(pniRegistrationId)) {
          pniRegistrationId = KeyHelper.generateRegistrationId(false)
        }
        pniRegistrationIds[deviceId] = pniRegistrationId

        // Device Messages
        if (deviceId != primaryDeviceId) {
          val pniChangeNumber = SyncMessage.PniChangeNumber(
            identityKeyPair = pniIdentity.serialize().toByteString(),
            signedPreKey = signedPreKeyRecord.serialize().toByteString(),
            lastResortKyberPreKey = lastResortKyberPreKeyRecord.serialize().toByteString(),
            registrationId = pniRegistrationId,
            newE164 = newE164
          )

          deviceMessages += messageSender.getEncryptedSyncPniInitializeDeviceMessage(deviceId, pniChangeNumber)
        }
      }

    val request = ChangePhoneNumberRequest(
      sessionId,
      recoveryPassword,
      newE164,
      registrationLock,
      pniIdentity.publicKey,
      deviceMessages,
      devicePniSignedPreKeys.mapKeys { it.key.toString() },
      devicePniLastResortKyberPreKeys.mapKeys { it.key.toString() },
      pniRegistrationIds.mapKeys { it.key.toString() }
    )

    val metadata = PendingChangeNumberMetadata(
      previousPni = GenZappStore.account.pni!!.toByteString(),
      pniIdentityKeyPair = pniIdentity.serialize().toByteString(),
      pniRegistrationId = pniRegistrationIds[primaryDeviceId]!!,
      pniSignedPreKeyId = devicePniSignedPreKeys[primaryDeviceId]!!.keyId,
      pniLastResortKyberPreKeyId = devicePniLastResortKyberPreKeys[primaryDeviceId]!!.keyId
    )

    return ChangeNumberRequestData(request, metadata)
  }

  private data class ChangeNumberRequestData(val changeNumberRequest: ChangePhoneNumberRequest, val pendingChangeNumberMetadata: PendingChangeNumberMetadata)

  data class NumberChangeResult(
    val uuid: String,
    val pni: String,
    val storageCapable: Boolean,
    val number: String
  )
}
