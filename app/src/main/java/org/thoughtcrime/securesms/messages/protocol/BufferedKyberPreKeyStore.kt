/*
 * Copyright 2023 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.messages.protocol

import org.GenZapp.libGenZapp.protocol.InvalidKeyIdException
import org.GenZapp.libGenZapp.protocol.state.KyberPreKeyRecord
import org.thoughtcrime.securesms.database.KyberPreKeyTable.KyberPreKey
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.whispersystems.GenZappservice.api.GenZappServiceAccountDataStore
import org.whispersystems.GenZappservice.api.GenZappServiceKyberPreKeyStore
import org.whispersystems.GenZappservice.api.push.ServiceId

/**
 * An in-memory kyber prekey store that is intended to be used temporarily while decrypting messages.
 */
class BufferedKyberPreKeyStore(private val selfServiceId: ServiceId) : GenZappServiceKyberPreKeyStore {

  /** Our in-memory cache of kyber prekeys. */
  val store: MutableMap<Int, KyberPreKey> = mutableMapOf()

  /** Whether or not we've done a loadAll operation. Let's us avoid doing it twice. */
  private var hasLoadedAll: Boolean = false

  /** The kyber prekeys that have been marked as removed (if they're not last resort). */
  private val removedIfNotLastResort: MutableSet<Int> = mutableSetOf()

  @kotlin.jvm.Throws(InvalidKeyIdException::class)
  override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
    return store.computeIfAbsent(kyberPreKeyId) {
      GenZappDatabase.kyberPreKeys.get(selfServiceId, kyberPreKeyId) ?: throw InvalidKeyIdException("Missing kyber prekey with ID: $kyberPreKeyId")
    }.record
  }

  override fun loadKyberPreKeys(): List<KyberPreKeyRecord> {
    return if (hasLoadedAll) {
      store.values.map { it.record }
    } else {
      val models = GenZappDatabase.kyberPreKeys.getAll(selfServiceId)
      models.forEach { store[it.record.id] = it }
      hasLoadedAll = true

      models.map { it.record }
    }
  }

  override fun loadLastResortKyberPreKeys(): List<KyberPreKeyRecord> {
    error("Not expected in this flow")
  }

  override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
    error("Not expected in this flow")
  }

  override fun storeLastResortKyberPreKey(kyberPreKeyId: Int, kyberPreKeyRecord: KyberPreKeyRecord) {
    error("Not expected in this flow")
  }

  override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean {
    loadKyberPreKey(kyberPreKeyId)
    return store.containsKey(kyberPreKeyId)
  }

  override fun markKyberPreKeyUsed(kyberPreKeyId: Int) {
    loadKyberPreKey(kyberPreKeyId)

    store[kyberPreKeyId]?.let {
      if (!it.lastResort) {
        store.remove(kyberPreKeyId)
      }
    }

    removedIfNotLastResort += kyberPreKeyId
  }

  override fun removeKyberPreKey(kyberPreKeyId: Int) {
    error("Not expected in this flow")
  }

  override fun markAllOneTimeKyberPreKeysStaleIfNecessary(staleTime: Long) {
    error("Not expected in this flow")
  }

  override fun deleteAllStaleOneTimeKyberPreKeys(threshold: Long, minCount: Int) {
    error("Not expected in this flow")
  }

  fun flushToDisk(persistentStore: GenZappServiceAccountDataStore) {
    for (id in removedIfNotLastResort) {
      persistentStore.markKyberPreKeyUsed(id)
    }
  }
}
