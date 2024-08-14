package org.thoughtcrime.securesms.messages.protocol

import org.GenZapp.libGenZapp.protocol.NoSessionException
import org.GenZapp.libGenZapp.protocol.GenZappProtocolAddress
import org.GenZapp.libGenZapp.protocol.state.SessionRecord
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.whispersystems.GenZappservice.api.GenZappServiceAccountDataStore
import org.whispersystems.GenZappservice.api.GenZappServiceSessionStore
import org.whispersystems.GenZappservice.api.push.ServiceId
import kotlin.jvm.Throws

/**
 * An in-memory session store that is intended to be used temporarily while decrypting messages.
 */
class BufferedSessionStore(private val selfServiceId: ServiceId) : GenZappServiceSessionStore {

  private val store: MutableMap<GenZappProtocolAddress, SessionRecord> = HashMap()

  /** All of the sessions that have been created or updated during operation. */
  private val updatedSessions: MutableMap<GenZappProtocolAddress, SessionRecord> = mutableMapOf()

  /** All of the sessions that have deleted during operation. */
  private val deletedSessions: MutableSet<GenZappProtocolAddress> = mutableSetOf()

  override fun loadSession(address: GenZappProtocolAddress): SessionRecord {
    val session: SessionRecord = store[address]
      ?: GenZappDatabase.sessions.load(selfServiceId, address)
      ?: SessionRecord()

    store[address] = session

    return session
  }

  @Throws(NoSessionException::class)
  override fun loadExistingSessions(addresses: MutableList<GenZappProtocolAddress>): List<SessionRecord> {
    val found: MutableList<SessionRecord?> = ArrayList(addresses.size)
    val needsDatabaseLookup: MutableList<Pair<Int, GenZappProtocolAddress>> = mutableListOf()

    addresses.forEachIndexed { index, address ->
      val cached: SessionRecord? = store[address]

      if (cached != null) {
        found[index] = cached
      } else {
        needsDatabaseLookup += (index to address)
      }
    }

    if (needsDatabaseLookup.isNotEmpty()) {
      val databaseRecords: List<SessionRecord?> = GenZappDatabase.sessions.load(selfServiceId, needsDatabaseLookup.map { (_, address) -> address })
      needsDatabaseLookup.forEachIndexed { databaseLookupIndex, (addressIndex, _) ->
        found[addressIndex] = databaseRecords[databaseLookupIndex]
      }
    }

    val cachedAndLoaded = found.filterNotNull()

    if (cachedAndLoaded.size != addresses.size) {
      throw NoSessionException("Failed to find one or more sessions.")
    }

    return cachedAndLoaded
  }

  override fun storeSession(address: GenZappProtocolAddress, record: SessionRecord) {
    store[address] = record
    updatedSessions[address] = record
  }

  override fun containsSession(address: GenZappProtocolAddress): Boolean {
    return if (store.containsKey(address)) {
      true
    } else {
      val fromDatabase: SessionRecord? = GenZappDatabase.sessions.load(selfServiceId, address)

      if (fromDatabase != null) {
        store[address] = fromDatabase
        return fromDatabase.hasSenderChain()
      } else {
        false
      }
    }
  }

  override fun deleteSession(address: GenZappProtocolAddress) {
    store.remove(address)
    deletedSessions += address
  }

  override fun getSubDeviceSessions(name: String): MutableList<Int> {
    error("Should not happen during the intended usage pattern of this class")
  }

  override fun deleteAllSessions(name: String) {
    error("Should not happen during the intended usage pattern of this class")
  }

  override fun archiveSession(address: GenZappProtocolAddress?) {
    error("Should not happen during the intended usage pattern of this class")
  }

  override fun getAllAddressesWithActiveSessions(addressNames: MutableList<String>): Map<GenZappProtocolAddress, SessionRecord> {
    error("Should not happen during the intended usage pattern of this class")
  }

  fun flushToDisk(persistentStore: GenZappServiceAccountDataStore) {
    for ((address, record) in updatedSessions) {
      persistentStore.storeSession(address, record)
    }

    for (address in deletedSessions) {
      persistentStore.deleteSession(address)
    }

    updatedSessions.clear()
    deletedSessions.clear()
  }
}
