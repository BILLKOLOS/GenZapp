package org.thoughtcrime.securesms.messages.protocol

import org.GenZapp.libGenZapp.protocol.InvalidKeyIdException
import org.GenZapp.libGenZapp.protocol.state.PreKeyRecord
import org.GenZapp.libGenZapp.protocol.state.PreKeyStore
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.whispersystems.GenZappservice.api.GenZappServiceAccountDataStore
import org.whispersystems.GenZappservice.api.push.ServiceId

/**
 * An in-memory one-time prekey store that is intended to be used temporarily while decrypting messages.
 */
class BufferedOneTimePreKeyStore(private val selfServiceId: ServiceId) : PreKeyStore {

  /** Our in-memory cache of one-time prekeys. */
  private val store: MutableMap<Int, PreKeyRecord> = HashMap()

  /** The one-time prekeys that have been marked as removed  */
  private val removed: MutableList<Int> = mutableListOf()

  @kotlin.jvm.Throws(InvalidKeyIdException::class)
  override fun loadPreKey(id: Int): PreKeyRecord {
    return store.computeIfAbsent(id) {
      GenZappDatabase.oneTimePreKeys.get(selfServiceId, id) ?: throw InvalidKeyIdException("Missing one-time prekey with ID: $id")
    }
  }

  override fun storePreKey(id: Int, record: PreKeyRecord) {
    error("Should not happen during the intended usage pattern of this class")
  }

  override fun containsPreKey(id: Int): Boolean {
    loadPreKey(id)
    return store.containsKey(id)
  }

  override fun removePreKey(id: Int) {
    store.remove(id)
    removed += id
  }

  fun flushToDisk(persistentStore: GenZappServiceAccountDataStore) {
    for (id in removed) {
      persistentStore.removePreKey(id)
    }

    removed.clear()
  }
}
