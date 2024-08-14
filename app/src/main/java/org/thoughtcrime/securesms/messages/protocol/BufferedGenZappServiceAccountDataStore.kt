package org.thoughtcrime.securesms.messages.protocol

import org.GenZapp.libGenZapp.protocol.IdentityKey
import org.GenZapp.libGenZapp.protocol.IdentityKeyPair
import org.GenZapp.libGenZapp.protocol.GenZappProtocolAddress
import org.GenZapp.libGenZapp.protocol.groups.state.SenderKeyRecord
import org.GenZapp.libGenZapp.protocol.state.IdentityKeyStore
import org.GenZapp.libGenZapp.protocol.state.KyberPreKeyRecord
import org.GenZapp.libGenZapp.protocol.state.PreKeyRecord
import org.GenZapp.libGenZapp.protocol.state.SessionRecord
import org.GenZapp.libGenZapp.protocol.state.SignedPreKeyRecord
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.whispersystems.GenZappservice.api.GenZappServiceAccountDataStore
import org.whispersystems.GenZappservice.api.push.DistributionId
import org.whispersystems.GenZappservice.api.push.ServiceId
import java.util.UUID

/**
 * The wrapper around all of the Buffered protocol stores. Designed to perform operations in memory,
 * then [flushToDisk] at set intervals.
 */
class BufferedGenZappServiceAccountDataStore(selfServiceId: ServiceId) : GenZappServiceAccountDataStore {

  private val identityStore: BufferedIdentityKeyStore = if (selfServiceId == GenZappStore.account.pni) {
    BufferedIdentityKeyStore(selfServiceId, GenZappStore.account.pniIdentityKey, GenZappStore.account.pniRegistrationId)
  } else {
    BufferedIdentityKeyStore(selfServiceId, GenZappStore.account.aciIdentityKey, GenZappStore.account.registrationId)
  }

  private val oneTimePreKeyStore: BufferedOneTimePreKeyStore = BufferedOneTimePreKeyStore(selfServiceId)
  private val signedPreKeyStore: BufferedSignedPreKeyStore = BufferedSignedPreKeyStore(selfServiceId)
  private val kyberPreKeyStore: BufferedKyberPreKeyStore = BufferedKyberPreKeyStore(selfServiceId)
  private val sessionStore: BufferedSessionStore = BufferedSessionStore(selfServiceId)
  private val senderKeyStore: BufferedSenderKeyStore = BufferedSenderKeyStore()

  override fun getIdentityKeyPair(): IdentityKeyPair {
    return identityStore.identityKeyPair
  }

  override fun getLocalRegistrationId(): Int {
    return identityStore.localRegistrationId
  }

  override fun saveIdentity(address: GenZappProtocolAddress, identityKey: IdentityKey): Boolean {
    return identityStore.saveIdentity(address, identityKey)
  }

  override fun isTrustedIdentity(address: GenZappProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean {
    return identityStore.isTrustedIdentity(address, identityKey, direction)
  }

  override fun getIdentity(address: GenZappProtocolAddress): IdentityKey? {
    return identityStore.getIdentity(address)
  }

  override fun loadPreKey(preKeyId: Int): PreKeyRecord {
    return oneTimePreKeyStore.loadPreKey(preKeyId)
  }

  override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
    return oneTimePreKeyStore.storePreKey(preKeyId, record)
  }

  override fun containsPreKey(preKeyId: Int): Boolean {
    return oneTimePreKeyStore.containsPreKey(preKeyId)
  }

  override fun removePreKey(preKeyId: Int) {
    oneTimePreKeyStore.removePreKey(preKeyId)
  }

  override fun loadSession(address: GenZappProtocolAddress): SessionRecord {
    return sessionStore.loadSession(address)
  }

  override fun loadExistingSessions(addresses: MutableList<GenZappProtocolAddress>): List<SessionRecord> {
    return sessionStore.loadExistingSessions(addresses)
  }

  override fun getSubDeviceSessions(name: String): MutableList<Int> {
    return sessionStore.getSubDeviceSessions(name)
  }

  override fun storeSession(address: GenZappProtocolAddress, record: SessionRecord) {
    sessionStore.storeSession(address, record)
  }

  override fun containsSession(address: GenZappProtocolAddress): Boolean {
    return sessionStore.containsSession(address)
  }

  override fun deleteSession(address: GenZappProtocolAddress) {
    return sessionStore.deleteSession(address)
  }

  override fun deleteAllSessions(name: String) {
    sessionStore.deleteAllSessions(name)
  }

  override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
    return signedPreKeyStore.loadSignedPreKey(signedPreKeyId)
  }

  override fun loadSignedPreKeys(): List<SignedPreKeyRecord> {
    return signedPreKeyStore.loadSignedPreKeys()
  }

  override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
    signedPreKeyStore.storeSignedPreKey(signedPreKeyId, record)
  }

  override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
    return signedPreKeyStore.containsSignedPreKey(signedPreKeyId)
  }

  override fun removeSignedPreKey(signedPreKeyId: Int) {
    signedPreKeyStore.removeSignedPreKey(signedPreKeyId)
  }

  override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
    return kyberPreKeyStore.loadKyberPreKey(kyberPreKeyId)
  }

  override fun loadKyberPreKeys(): List<KyberPreKeyRecord> {
    return kyberPreKeyStore.loadKyberPreKeys()
  }

  override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
    kyberPreKeyStore.storeKyberPreKey(kyberPreKeyId, record)
  }

  override fun storeLastResortKyberPreKey(kyberPreKeyId: Int, kyberPreKeyRecord: KyberPreKeyRecord) {
    kyberPreKeyStore.storeKyberPreKey(kyberPreKeyId, kyberPreKeyRecord)
  }

  override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean {
    return kyberPreKeyStore.containsKyberPreKey(kyberPreKeyId)
  }

  override fun markKyberPreKeyUsed(kyberPreKeyId: Int) {
    return kyberPreKeyStore.markKyberPreKeyUsed(kyberPreKeyId)
  }

  override fun deleteAllStaleOneTimeEcPreKeys(threshold: Long, minCount: Int) {
    error("Should not happen during the intended usage pattern of this class")
  }

  override fun markAllOneTimeEcPreKeysStaleIfNecessary(staleTime: Long) {
    error("Should not happen during the intended usage pattern of this class")
  }

  override fun removeKyberPreKey(kyberPreKeyId: Int) {
    kyberPreKeyStore.removeKyberPreKey(kyberPreKeyId)
  }

  override fun markAllOneTimeKyberPreKeysStaleIfNecessary(staleTime: Long) {
    kyberPreKeyStore.markAllOneTimeKyberPreKeysStaleIfNecessary(staleTime)
  }

  override fun deleteAllStaleOneTimeKyberPreKeys(threshold: Long, minCount: Int) {
    kyberPreKeyStore.deleteAllStaleOneTimeKyberPreKeys(threshold, minCount)
  }

  override fun loadLastResortKyberPreKeys(): List<KyberPreKeyRecord> {
    return kyberPreKeyStore.loadLastResortKyberPreKeys()
  }

  override fun storeSenderKey(sender: GenZappProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
    senderKeyStore.storeSenderKey(sender, distributionId, record)
  }

  override fun loadSenderKey(sender: GenZappProtocolAddress, distributionId: UUID): SenderKeyRecord? {
    return senderKeyStore.loadSenderKey(sender, distributionId)
  }

  override fun archiveSession(address: GenZappProtocolAddress?) {
    sessionStore.archiveSession(address)
  }

  override fun getAllAddressesWithActiveSessions(addressNames: MutableList<String>): Map<GenZappProtocolAddress, SessionRecord> {
    return sessionStore.getAllAddressesWithActiveSessions(addressNames)
  }

  override fun getSenderKeySharedWith(distributionId: DistributionId?): MutableSet<GenZappProtocolAddress> {
    return senderKeyStore.getSenderKeySharedWith(distributionId)
  }

  override fun markSenderKeySharedWith(distributionId: DistributionId, addresses: MutableCollection<GenZappProtocolAddress>) {
    senderKeyStore.markSenderKeySharedWith(distributionId, addresses)
  }

  override fun clearSenderKeySharedWith(addresses: MutableCollection<GenZappProtocolAddress>) {
    senderKeyStore.clearSenderKeySharedWith(addresses)
  }

  override fun isMultiDevice(): Boolean {
    error("Should not happen during the intended usage pattern of this class")
  }

  fun flushToDisk(persistentStore: GenZappServiceAccountDataStore) {
    identityStore.flushToDisk(persistentStore)
    oneTimePreKeyStore.flushToDisk(persistentStore)
    kyberPreKeyStore.flushToDisk(persistentStore)
    signedPreKeyStore.flushToDisk(persistentStore)
    sessionStore.flushToDisk(persistentStore)
    senderKeyStore.flushToDisk(persistentStore)
  }
}
