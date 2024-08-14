package org.thoughtcrime.securesms.testing

import org.GenZapp.core.util.readToSingleInt
import org.GenZapp.core.util.select
import org.GenZapp.libGenZapp.protocol.IdentityKey
import org.GenZapp.libGenZapp.protocol.IdentityKeyPair
import org.GenZapp.libGenZapp.protocol.SessionBuilder
import org.GenZapp.libGenZapp.protocol.GenZappProtocolAddress
import org.GenZapp.libGenZapp.protocol.ecc.ECKeyPair
import org.GenZapp.libGenZapp.protocol.groups.state.SenderKeyRecord
import org.GenZapp.libGenZapp.protocol.state.IdentityKeyStore
import org.GenZapp.libGenZapp.protocol.state.KyberPreKeyRecord
import org.GenZapp.libGenZapp.protocol.state.PreKeyBundle
import org.GenZapp.libGenZapp.protocol.state.PreKeyRecord
import org.GenZapp.libGenZapp.protocol.state.SessionRecord
import org.GenZapp.libGenZapp.protocol.state.SignedPreKeyRecord
import org.GenZapp.libGenZapp.protocol.util.KeyHelper
import org.GenZapp.libGenZapp.zkgroup.profiles.ProfileKey
import org.thoughtcrime.securesms.crypto.ProfileKeyUtil
import org.thoughtcrime.securesms.crypto.SealedSenderAccessUtil
import org.thoughtcrime.securesms.database.OneTimePreKeyTable
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.database.SignedPreKeyTable
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.testing.FakeClientHelpers.toEnvelope
import org.whispersystems.GenZappservice.api.GenZappServiceAccountDataStore
import org.whispersystems.GenZappservice.api.GenZappSessionLock
import org.whispersystems.GenZappservice.api.crypto.SealedSenderAccess
import org.whispersystems.GenZappservice.api.crypto.GenZappServiceCipher
import org.whispersystems.GenZappservice.api.crypto.GenZappSessionBuilder
import org.whispersystems.GenZappservice.api.push.DistributionId
import org.whispersystems.GenZappservice.api.push.ServiceId
import org.whispersystems.GenZappservice.api.push.GenZappServiceAddress
import org.whispersystems.GenZappservice.internal.push.Envelope
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

/**
 * Welcome to Bob's Client.
 *
 * Bob is a "fake" client that can start a session with the Android instrumentation test user (Alice).
 *
 * Bob can create a new session using a prekey bundle created from Alice's prekeys, send a message, decrypt
 * a return message from Alice, and that'll start a standard GenZapp session with normal keys/ratcheting.
 */
class BobClient(val serviceId: ServiceId, val e164: String, val identityKeyPair: IdentityKeyPair, val trustRoot: ECKeyPair, val profileKey: ProfileKey) {

  private val serviceAddress = GenZappServiceAddress(serviceId, e164)
  private val registrationId = KeyHelper.generateRegistrationId(false)
  private val aciStore = BobGenZappServiceAccountDataStore(registrationId, identityKeyPair)
  private val senderCertificate = FakeClientHelpers.createCertificateFor(trustRoot, serviceId.rawUuid, e164, 1, identityKeyPair.publicKey.publicKey, 31337)
  private val sessionLock = object : GenZappSessionLock {
    private val lock = ReentrantLock()

    override fun acquire(): GenZappSessionLock.Lock {
      lock.lock()
      return GenZappSessionLock.Lock { lock.unlock() }
    }
  }

  /** Inspired by GenZappServiceMessageSender#getEncryptedMessage */
  fun encrypt(now: Long): Envelope {
    val envelopeContent = FakeClientHelpers.encryptedTextMessage(now)

    val cipher = GenZappServiceCipher(serviceAddress, 1, aciStore, sessionLock, null)

    if (!aciStore.containsSession(getAliceProtocolAddress())) {
      val sessionBuilder = GenZappSessionBuilder(sessionLock, SessionBuilder(aciStore, getAliceProtocolAddress()))
      sessionBuilder.process(getAlicePreKeyBundle())
    }

    return cipher.encrypt(getAliceProtocolAddress(), getAliceUnidentifiedAccess(), envelopeContent)
      .toEnvelope(envelopeContent.content.get().dataMessage!!.timestamp!!, getAliceServiceId())
  }

  fun decrypt(envelope: Envelope, serverDeliveredTimestamp: Long) {
    val cipher = GenZappServiceCipher(serviceAddress, 1, aciStore, sessionLock, SealedSenderAccessUtil.getCertificateValidator())
    cipher.decrypt(envelope, serverDeliveredTimestamp)
  }

  private fun getAliceServiceId(): ServiceId {
    return GenZappStore.account.requireAci()
  }

  private fun getAlicePreKeyBundle(): PreKeyBundle {
    val selfPreKeyId = GenZappDatabase.rawDatabase
      .select(OneTimePreKeyTable.KEY_ID)
      .from(OneTimePreKeyTable.TABLE_NAME)
      .where("${OneTimePreKeyTable.ACCOUNT_ID} = ?", getAliceServiceId().toString())
      .run()
      .readToSingleInt(-1)

    val selfPreKeyRecord = GenZappDatabase.oneTimePreKeys.get(getAliceServiceId(), selfPreKeyId)!!

    val selfSignedPreKeyId = GenZappDatabase.rawDatabase
      .select(SignedPreKeyTable.KEY_ID)
      .from(SignedPreKeyTable.TABLE_NAME)
      .where("${SignedPreKeyTable.ACCOUNT_ID} = ?", getAliceServiceId().toString())
      .run()
      .readToSingleInt(-1)

    val selfSignedPreKeyRecord = GenZappDatabase.signedPreKeys.get(getAliceServiceId(), selfSignedPreKeyId)!!

    return PreKeyBundle(
      GenZappStore.account.registrationId,
      1,
      selfPreKeyId,
      selfPreKeyRecord.keyPair.publicKey,
      selfSignedPreKeyId,
      selfSignedPreKeyRecord.keyPair.publicKey,
      selfSignedPreKeyRecord.signature,
      getAlicePublicKey()
    )
  }

  private fun getAliceProtocolAddress(): GenZappProtocolAddress {
    return GenZappProtocolAddress(GenZappStore.account.requireAci().toString(), 1)
  }

  private fun getAlicePublicKey(): IdentityKey {
    return GenZappStore.account.aciIdentityKey.publicKey
  }

  private fun getAliceProfileKey(): ProfileKey {
    return ProfileKeyUtil.getSelfProfileKey()
  }

  private fun getAliceUnidentifiedAccess(): SealedSenderAccess? {
    return FakeClientHelpers.getSealedSenderAccess(getAliceProfileKey(), senderCertificate)
  }

  private class BobGenZappServiceAccountDataStore(private val registrationId: Int, private val identityKeyPair: IdentityKeyPair) : GenZappServiceAccountDataStore {
    private var aliceSessionRecord: SessionRecord? = null

    override fun getIdentityKeyPair(): IdentityKeyPair = identityKeyPair

    override fun getLocalRegistrationId(): Int = registrationId
    override fun isTrustedIdentity(address: GenZappProtocolAddress?, identityKey: IdentityKey?, direction: IdentityKeyStore.Direction?): Boolean = true
    override fun loadSession(address: GenZappProtocolAddress?): SessionRecord = aliceSessionRecord ?: SessionRecord()
    override fun saveIdentity(address: GenZappProtocolAddress?, identityKey: IdentityKey?): Boolean = false
    override fun storeSession(address: GenZappProtocolAddress?, record: SessionRecord?) {
      aliceSessionRecord = record
    }
    override fun getSubDeviceSessions(name: String?): List<Int> = emptyList()
    override fun containsSession(address: GenZappProtocolAddress?): Boolean = aliceSessionRecord != null
    override fun getIdentity(address: GenZappProtocolAddress?): IdentityKey = GenZappStore.account.aciIdentityKey.publicKey
    override fun loadPreKey(preKeyId: Int): PreKeyRecord = throw UnsupportedOperationException()
    override fun storePreKey(preKeyId: Int, record: PreKeyRecord?) = throw UnsupportedOperationException()
    override fun containsPreKey(preKeyId: Int): Boolean = throw UnsupportedOperationException()
    override fun removePreKey(preKeyId: Int) = throw UnsupportedOperationException()
    override fun loadExistingSessions(addresses: MutableList<GenZappProtocolAddress>?): MutableList<SessionRecord> = throw UnsupportedOperationException()
    override fun deleteSession(address: GenZappProtocolAddress?) = throw UnsupportedOperationException()
    override fun deleteAllSessions(name: String?) = throw UnsupportedOperationException()
    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord = throw UnsupportedOperationException()
    override fun loadSignedPreKeys(): MutableList<SignedPreKeyRecord> = throw UnsupportedOperationException()
    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord?) = throw UnsupportedOperationException()
    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = throw UnsupportedOperationException()
    override fun removeSignedPreKey(signedPreKeyId: Int) = throw UnsupportedOperationException()
    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord = throw UnsupportedOperationException()
    override fun loadKyberPreKeys(): MutableList<KyberPreKeyRecord> = throw UnsupportedOperationException()
    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord?) = throw UnsupportedOperationException()
    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = throw UnsupportedOperationException()
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int) = throw UnsupportedOperationException()
    override fun deleteAllStaleOneTimeEcPreKeys(threshold: Long, minCount: Int) = throw UnsupportedOperationException()
    override fun markAllOneTimeEcPreKeysStaleIfNecessary(staleTime: Long) = throw UnsupportedOperationException()
    override fun storeSenderKey(sender: GenZappProtocolAddress?, distributionId: UUID?, record: SenderKeyRecord?) = throw UnsupportedOperationException()
    override fun loadSenderKey(sender: GenZappProtocolAddress?, distributionId: UUID?): SenderKeyRecord = throw UnsupportedOperationException()
    override fun archiveSession(address: GenZappProtocolAddress?) = throw UnsupportedOperationException()
    override fun getAllAddressesWithActiveSessions(addressNames: MutableList<String>?): MutableMap<GenZappProtocolAddress, SessionRecord> = throw UnsupportedOperationException()
    override fun getSenderKeySharedWith(distributionId: DistributionId?): MutableSet<GenZappProtocolAddress> = throw UnsupportedOperationException()
    override fun markSenderKeySharedWith(distributionId: DistributionId?, addresses: MutableCollection<GenZappProtocolAddress>?) = throw UnsupportedOperationException()
    override fun clearSenderKeySharedWith(addresses: MutableCollection<GenZappProtocolAddress>?) = throw UnsupportedOperationException()
    override fun storeLastResortKyberPreKey(kyberPreKeyId: Int, kyberPreKeyRecord: KyberPreKeyRecord) = throw UnsupportedOperationException()
    override fun removeKyberPreKey(kyberPreKeyId: Int) = throw UnsupportedOperationException()
    override fun markAllOneTimeKyberPreKeysStaleIfNecessary(staleTime: Long) = throw UnsupportedOperationException()
    override fun deleteAllStaleOneTimeKyberPreKeys(threshold: Long, minCount: Int) = throw UnsupportedOperationException()
    override fun loadLastResortKyberPreKeys(): List<KyberPreKeyRecord> = throw UnsupportedOperationException()
    override fun isMultiDevice(): Boolean = throw UnsupportedOperationException()
  }
}
