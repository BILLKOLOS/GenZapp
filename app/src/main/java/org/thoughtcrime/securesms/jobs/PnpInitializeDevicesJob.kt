package org.thoughtcrime.securesms.jobs

import androidx.annotation.WorkerThread
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import okio.ByteString.Companion.toByteString
import org.GenZapp.core.util.concurrent.safeBlockingGet
import org.GenZapp.core.util.logging.Log
import org.GenZapp.core.util.orNull
import org.GenZapp.libGenZapp.protocol.IdentityKeyPair
import org.GenZapp.libGenZapp.protocol.GenZappProtocolAddress
import org.GenZapp.libGenZapp.protocol.state.KyberPreKeyRecord
import org.GenZapp.libGenZapp.protocol.state.GenZappProtocolStore
import org.GenZapp.libGenZapp.protocol.state.SignedPreKeyRecord
import org.GenZapp.libGenZapp.protocol.util.KeyHelper
import org.GenZapp.libGenZapp.protocol.util.Medium
import org.thoughtcrime.securesms.components.settings.app.changenumber.ChangeNumberViewModel
import org.thoughtcrime.securesms.crypto.PreKeyUtil
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.registration.VerifyResponse
import org.thoughtcrime.securesms.registration.VerifyResponseWithoutKbs
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.whispersystems.GenZappservice.api.account.PniKeyDistributionRequest
import org.whispersystems.GenZappservice.api.push.GenZappServiceAddress
import org.whispersystems.GenZappservice.api.push.SignedPreKeyEntity
import org.whispersystems.GenZappservice.internal.ServiceResponse
import org.whispersystems.GenZappservice.internal.push.KyberPreKeyEntity
import org.whispersystems.GenZappservice.internal.push.OutgoingPushMessage
import org.whispersystems.GenZappservice.internal.push.SyncMessage
import org.whispersystems.GenZappservice.internal.push.VerifyAccountResponse
import org.whispersystems.GenZappservice.internal.push.exceptions.MismatchedDevicesException
import java.io.IOException
import java.security.SecureRandom

/**
 * To be run when all clients support PNP and we need to initialize all linked devices with appropriate PNP data.
 *
 * We reuse the change number flow as it already support distributing the necessary data in a way linked devices can understand.
 */
class PnpInitializeDevicesJob private constructor(parameters: Parameters) : BaseJob(parameters) {

  companion object {
    const val KEY = "PnpInitializeDevicesJob"
    private val TAG = Log.tag(PnpInitializeDevicesJob::class.java)

    @JvmStatic
    fun enqueueIfNecessary() {
      if (GenZappStore.misc.hasPniInitializedDevices || !GenZappStore.account.isRegistered || GenZappStore.account.aci == null) {
        return
      }

      AppDependencies.jobManager.add(PnpInitializeDevicesJob())
    }
  }

  constructor() : this(Parameters.Builder().addConstraint(NetworkConstraint.KEY).build())

  override fun serialize(): ByteArray? {
    return null
  }

  override fun getFactoryKey(): String {
    return KEY
  }

  override fun onFailure() = Unit

  @Throws(Exception::class)
  public override fun onRun() {
    if (!GenZappStore.account.isRegistered || GenZappStore.account.aci == null) {
      Log.w(TAG, "Not registered! Skipping, as it wouldn't do anything.")
      return
    }

    if (!TextSecurePreferences.isMultiDevice(context)) {
      Log.i(TAG, "Not multi device, aborting...")
      GenZappStore.misc.hasPniInitializedDevices = true
      return
    }

    if (GenZappStore.account.isLinkedDevice) {
      Log.i(TAG, "Not primary device, aborting...")
      GenZappStore.misc.hasPniInitializedDevices = true
      return
    }

    ChangeNumberViewModel.CHANGE_NUMBER_LOCK.lock()
    try {
      if (GenZappStore.misc.hasPniInitializedDevices) {
        Log.w(TAG, "We found out that things have been initialized after we got the lock! No need to do anything else.")
        return
      }

      val e164 = GenZappStore.account.requireE164()

      try {
        Log.i(TAG, "Initializing PNI for linked devices")
        val result: VerifyResponseWithoutKbs = initializeDevices(e164)
          .map(::VerifyResponseWithoutKbs)
          .safeBlockingGet()

        result.error?.let { throw it }
      } catch (e: InterruptedException) {
        throw IOException("Retry", e)
      } catch (t: Throwable) {
        Log.w(TAG, "Unable to initialize PNI for linked devices", t)
        throw t
      }

      GenZappStore.misc.hasPniInitializedDevices = true
    } finally {
      ChangeNumberViewModel.CHANGE_NUMBER_LOCK.unlock()
    }
  }

  private fun initializeDevices(newE164: String): Single<ServiceResponse<VerifyResponse>> {
    val accountManager = AppDependencies.GenZappServiceAccountManager
    val messageSender = AppDependencies.GenZappServiceMessageSender

    return Single.fromCallable {
      var completed = false
      var attempts = 0
      lateinit var distributionResponse: ServiceResponse<VerifyAccountResponse>

      while (!completed && attempts < 5) {
        val request = createInitializeDevicesRequest(
          newE164 = newE164
        )

        distributionResponse = accountManager.distributePniKeys(request)

        val possibleError: Throwable? = distributionResponse.applicationError.orNull()
        if (possibleError is MismatchedDevicesException) {
          messageSender.handleChangeNumberMismatchDevices(possibleError.mismatchedDevices)
          attempts++
        } else {
          completed = true
        }
      }

      VerifyResponse.from(
        response = distributionResponse,
        masterKey = null,
        pin = null,
        aciPreKeyCollection = null,
        pniPreKeyCollection = null
      )
    }.subscribeOn(Schedulers.single())
      .onErrorReturn { t -> ServiceResponse.forExecutionError(t) }
  }

  @WorkerThread
  private fun createInitializeDevicesRequest(
    newE164: String
  ): PniKeyDistributionRequest {
    val selfIdentifier: String = GenZappStore.account.requireAci().toString()
    val aciProtocolStore: GenZappProtocolStore = AppDependencies.protocolStore.aci()
    val pniProtocolStore: GenZappProtocolStore = AppDependencies.protocolStore.pni()
    val messageSender = AppDependencies.GenZappServiceMessageSender

    val pniIdentity: IdentityKeyPair = GenZappStore.account.pniIdentityKey
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
          pniProtocolStore.loadSignedPreKey(GenZappStore.account.pniPreKeys.activeSignedPreKeyId)
        } else {
          PreKeyUtil.generateSignedPreKey(SecureRandom().nextInt(Medium.MAX_VALUE), pniIdentity.privateKey)
        }
        devicePniSignedPreKeys[deviceId] = SignedPreKeyEntity(signedPreKeyRecord.id, signedPreKeyRecord.keyPair.publicKey, signedPreKeyRecord.signature)

        // Last-resort kyber prekeys
        val lastResortKyberPreKeyRecord: KyberPreKeyRecord = if (deviceId == primaryDeviceId) {
          pniProtocolStore.loadKyberPreKey(GenZappStore.account.pniPreKeys.lastResortKyberPreKeyId)
        } else {
          PreKeyUtil.generateLastResortKyberPreKey(SecureRandom().nextInt(Medium.MAX_VALUE), pniIdentity.privateKey)
        }
        devicePniLastResortKyberPreKeys[deviceId] = KyberPreKeyEntity(lastResortKyberPreKeyRecord.id, lastResortKyberPreKeyRecord.keyPair.publicKey, lastResortKyberPreKeyRecord.signature)

        // Registration Ids
        var pniRegistrationId = if (deviceId == primaryDeviceId) {
          GenZappStore.account.pniRegistrationId
        } else {
          -1
        }

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

    return PniKeyDistributionRequest(
      pniIdentity.publicKey,
      deviceMessages,
      devicePniSignedPreKeys.mapKeys { it.key.toString() },
      devicePniLastResortKyberPreKeys.mapKeys { it.key.toString() },
      pniRegistrationIds.mapKeys { it.key.toString() },
      true
    )
  }

  override fun onShouldRetry(e: Exception): Boolean {
    return e is IOException
  }

  class Factory : Job.Factory<PnpInitializeDevicesJob?> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): PnpInitializeDevicesJob {
      return PnpInitializeDevicesJob(parameters)
    }
  }
}
