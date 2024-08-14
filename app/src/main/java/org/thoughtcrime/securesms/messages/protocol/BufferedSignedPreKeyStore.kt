package org.thoughtcrime.securesms.messages.protocol

import org.GenZapp.libGenZapp.protocol.InvalidKeyIdException
import org.GenZapp.libGenZapp.protocol.state.SignedPreKeyRecord
import org.GenZapp.libGenZapp.protocol.state.SignedPreKeyStore
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.whispersystems.GenZappservice.api.GenZappServiceAccountDataStore
import org.whispersystems.GenZappservice.api.push.ServiceId

/**
 * An in-memory signed prekey store that is intended to be used temporarily while decrypting messages.
 */
class BufferedSignedPreKeyStore(private val selfServiceId: ServiceId) : SignedPreKeyStore {

  /** Our in-memory cache of signed prekeys. */
  private val store: MutableMap<Int, SignedPreKeyRecord> = HashMap()

  /** The signed prekeys that have been marked as removed  */
  private val removed: MutableList<Int> = mutableListOf()

  /** Whether or not we've done a loadAll operation. Let's us avoid doing it twice. */
  private var hasLoadedAll: Boolean = false

  @kotlin.jvm.Throws(InvalidKeyIdException::class)
  override fun loadSignedPreKey(id: Int): SignedPreKeyRecord {
    return store.computeIfAbsent(id) {
      GenZappDatabase.signedPreKeys.get(selfServiceId, id) ?: throw InvalidKeyIdException("Missing signed prekey with ID: $id")
    }
  }

  override fun loadSignedPreKeys(): List<SignedPreKeyRecord> {
    return if (hasLoadedAll) {
      store.values.toList()
    } else {
      val records = GenZappDatabase.signedPreKeys.getAll(selfServiceId)
      records.forEach { store[it.id] = it }
      hasLoadedAll = true

      records
    }
  }

  override fun storeSignedPreKey(id: Int, record: SignedPreKeyRecord) {
    error("Should not happen during the intended usage pattern of this class")
  }

  override fun containsSignedPreKey(id: Int): Boolean {
    loadSignedPreKey(id)
    return store.containsKey(id)
  }

  override fun removeSignedPreKey(id: Int) {
    store.remove(id)
    removed += id
  }

  fun flushToDisk(persistentStore: GenZappServiceAccountDataStore) {
    for (id in removed) {
      persistentStore.removeSignedPreKey(id)
    }

    removed.clear()
  }
}
