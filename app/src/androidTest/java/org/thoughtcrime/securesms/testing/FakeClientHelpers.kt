package org.thoughtcrime.securesms.testing

import okio.ByteString.Companion.toByteString
import org.GenZapp.core.util.Base64
import org.GenZapp.libGenZapp.internal.Native
import org.GenZapp.libGenZapp.internal.NativeHandleGuard
import org.GenZapp.libGenZapp.metadata.certificate.CertificateValidator
import org.GenZapp.libGenZapp.metadata.certificate.SenderCertificate
import org.GenZapp.libGenZapp.metadata.certificate.ServerCertificate
import org.GenZapp.libGenZapp.protocol.ecc.Curve
import org.GenZapp.libGenZapp.protocol.ecc.ECKeyPair
import org.GenZapp.libGenZapp.protocol.ecc.ECPublicKey
import org.GenZapp.libGenZapp.zkgroup.profiles.ProfileKey
import org.thoughtcrime.securesms.messages.GenZappServiceProtoUtil.buildWith
import org.whispersystems.GenZappservice.api.crypto.ContentHint
import org.whispersystems.GenZappservice.api.crypto.EnvelopeContent
import org.whispersystems.GenZappservice.api.crypto.SealedSenderAccess
import org.whispersystems.GenZappservice.api.crypto.UnidentifiedAccess
import org.whispersystems.GenZappservice.api.push.ServiceId
import org.whispersystems.GenZappservice.internal.push.Content
import org.whispersystems.GenZappservice.internal.push.DataMessage
import org.whispersystems.GenZappservice.internal.push.Envelope
import org.whispersystems.GenZappservice.internal.push.OutgoingPushMessage
import java.util.Optional
import java.util.UUID

object FakeClientHelpers {

  val noOpCertificateValidator = object : CertificateValidator(null) {
    override fun validate(certificate: SenderCertificate, validationTime: Long) = Unit
  }

  fun createCertificateFor(trustRoot: ECKeyPair, uuid: UUID, e164: String, deviceId: Int, identityKey: ECPublicKey, expires: Long): SenderCertificate {
    val serverKey: ECKeyPair = Curve.generateKeyPair()
    NativeHandleGuard(serverKey.publicKey).use { serverPublicGuard ->
      NativeHandleGuard(trustRoot.privateKey).use { trustRootPrivateGuard ->
        val serverCertificate = ServerCertificate(Native.ServerCertificate_New(1, serverPublicGuard.nativeHandle(), trustRootPrivateGuard.nativeHandle()))
        NativeHandleGuard(identityKey).use { identityGuard ->
          NativeHandleGuard(serverCertificate).use { serverCertificateGuard ->
            NativeHandleGuard(serverKey.privateKey).use { serverPrivateGuard ->
              return SenderCertificate(Native.SenderCertificate_New(uuid.toString(), e164, deviceId, identityGuard.nativeHandle(), expires, serverCertificateGuard.nativeHandle(), serverPrivateGuard.nativeHandle()))
            }
          }
        }
      }
    }
  }

  fun getSealedSenderAccess(theirProfileKey: ProfileKey, senderCertificate: SenderCertificate): SealedSenderAccess? {
    val themUnidentifiedAccessKey = UnidentifiedAccess(UnidentifiedAccess.deriveAccessKeyFrom(theirProfileKey), senderCertificate.serialized, false)

    return SealedSenderAccess.forIndividual(themUnidentifiedAccessKey)
  }

  fun encryptedTextMessage(now: Long, message: String = "Test body message"): EnvelopeContent {
    val content = Content.Builder().apply {
      dataMessage(
        DataMessage.Builder().buildWith {
          body = message
          timestamp = now
        }
      )
    }
    return EnvelopeContent.encrypted(content.build(), ContentHint.RESENDABLE, Optional.empty())
  }

  fun OutgoingPushMessage.toEnvelope(timestamp: Long, destination: ServiceId): Envelope {
    return Envelope.Builder()
      .type(Envelope.Type.fromValue(this.type))
      .sourceDevice(1)
      .timestamp(timestamp)
      .serverTimestamp(timestamp + 1)
      .destinationServiceId(destination.toString())
      .serverGuid(UUID.randomUUID().toString())
      .content(Base64.decode(this.content).toByteString())
      .urgent(true)
      .story(false)
      .build()
  }
}
