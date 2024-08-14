package org.thoughtcrime.securesms.messages.protocol

import org.GenZapp.libGenZapp.protocol.GenZappProtocolAddress
import org.GenZapp.libGenZapp.protocol.groups.state.SenderKeyRecord
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.whispersystems.GenZappservice.api.GenZappServiceAccountDataStore
import org.whispersystems.GenZappservice.api.GenZappServiceSenderKeyStore
import org.whispersystems.GenZappservice.api.push.DistributionId
import java.util.UUID

/**
 * An in-memory sender key store that is intended to be used temporarily while decrypting messages.
 */
class BufferedSenderKeyStore : GenZappServiceSenderKeyStore {

  private val store: MutableMap<StoreKey, SenderKeyRecord> = HashMap()

  /** All of the keys that have been created or updated during operation. */
  private val updatedKeys: MutableMap<StoreKey, SenderKeyRecord> = mutableMapOf()

  /** All of the distributionId's whose sharing has been cleared during operation. */
  private val clearSharedWith: MutableSet<GenZappProtocolAddress> = mutableSetOf()

  override fun storeSenderKey(sender: GenZappProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
    val key = StoreKey(sender, distributionId)
    store[key] = record
    updatedKeys[key] = record
  }

  override fun loadSenderKey(sender: GenZappProtocolAddress, distributionId: UUID): SenderKeyRecord? {
    val cached: SenderKeyRecord? = store[StoreKey(sender, distributionId)]

    return if (cached != null) {
      cached
    } else {
      val fromDatabase: SenderKeyRecord? = GenZappDatabase.senderKeys.load(sender, distributionId.toDistributionId())

      if (fromDatabase != null) {
        store[StoreKey(sender, distributionId)] = fromDatabase
      }

      return fromDatabase
    }
  }

  override fun clearSenderKeySharedWith(addresses: MutableCollection<GenZappProtocolAddress>) {
    clearSharedWith.addAll(addresses)
  }

  override fun getSenderKeySharedWith(distributionId: DistributionId?): MutableSet<GenZappProtocolAddress> {
    error("Should not happen during the intended usage pattern of this class")
  }

  override fun markSenderKeySharedWith(distributionId: DistributionId?, addresses: MutableCollection<GenZappProtocolAddress>?) {
    error("Should not happen during the intended usage pattern of this class")
  }

  fun flushToDisk(persistentStore: GenZappServiceAccountDataStore) {
    for ((key, record) in updatedKeys) {
      persistentStore.storeSenderKey(key.address, key.distributionId, record)
    }

    if (clearSharedWith.isNotEmpty()) {
      persistentStore.clearSenderKeySharedWith(clearSharedWith)
      clearSharedWith.clear()
    }

    updatedKeys.clear()
  }

  private fun UUID.toDistributionId() = DistributionId.from(this)

  data class StoreKey(
    val address: GenZappProtocolAddress,
    val distributionId: UUID
  )
}
