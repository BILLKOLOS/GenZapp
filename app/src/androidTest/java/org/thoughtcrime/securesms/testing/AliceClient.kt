package org.thoughtcrime.securesms.testing

import org.GenZapp.core.util.logging.Log
import org.GenZapp.libGenZapp.protocol.ecc.ECKeyPair
import org.GenZapp.libGenZapp.zkgroup.profiles.ProfileKey
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.messages.protocol.BufferedProtocolStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.testing.FakeClientHelpers.toEnvelope
import org.whispersystems.GenZappservice.api.push.ServiceId
import org.whispersystems.GenZappservice.api.push.GenZappServiceAddress
import org.whispersystems.GenZappservice.internal.push.Envelope

/**
 * Welcome to Alice's Client.
 *
 * Alice represent the Android instrumentation test user. Unlike [BobClient] much less is needed here
 * as it can make use of the standard GenZapp Android App infrastructure.
 */
class AliceClient(val serviceId: ServiceId, val e164: String, val trustRoot: ECKeyPair) {

  companion object {
    val TAG = Log.tag(AliceClient::class.java)
  }

  private val aliceSenderCertificate = FakeClientHelpers.createCertificateFor(
    trustRoot = trustRoot,
    uuid = serviceId.rawUuid,
    e164 = e164,
    deviceId = 1,
    identityKey = GenZappStore.account.aciIdentityKey.publicKey.publicKey,
    expires = 31337
  )

  fun process(envelope: Envelope, serverDeliveredTimestamp: Long) {
    val start = System.currentTimeMillis()
    val bufferedStore = BufferedProtocolStore.create()
    AppDependencies.incomingMessageObserver
      .processEnvelope(bufferedStore, envelope, serverDeliveredTimestamp)
      ?.mapNotNull { it.run() }
      ?.forEach { it.enqueue() }

    bufferedStore.flushToDisk()
    val end = System.currentTimeMillis()
    Log.d(TAG, "${end - start}")
  }

  fun encrypt(now: Long, destination: Recipient): Envelope {
    return AppDependencies.GenZappServiceMessageSender.getEncryptedMessage(
      GenZappServiceAddress(destination.requireServiceId(), destination.requireE164()),
      FakeClientHelpers.getSealedSenderAccess(ProfileKey(destination.profileKey), aliceSenderCertificate),
      1,
      FakeClientHelpers.encryptedTextMessage(now),
      false
    ).toEnvelope(now, destination.requireServiceId())
  }
}
