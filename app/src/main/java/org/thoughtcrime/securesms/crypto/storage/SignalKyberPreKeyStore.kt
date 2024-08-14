/*
 * Copyright 2023 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.crypto.storage

import org.GenZapp.libGenZapp.protocol.InvalidKeyIdException
import org.GenZapp.libGenZapp.protocol.state.KyberPreKeyRecord
import org.GenZapp.libGenZapp.protocol.state.KyberPreKeyStore
import org.thoughtcrime.securesms.crypto.ReentrantSessionLock
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.whispersystems.GenZappservice.api.GenZappServiceKyberPreKeyStore
import org.whispersystems.GenZappservice.api.push.ServiceId
import kotlin.jvm.Throws

/**
 * An implementation of the [KyberPreKeyStore] that stores entries in [org.thoughtcrime.securesms.database.KyberPreKeyTable].
 */
class GenZappKyberPreKeyStore(private val selfServiceId: ServiceId) : GenZappServiceKyberPreKeyStore {

  @Throws(InvalidKeyIdException::class)
  override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
    ReentrantSessionLock.INSTANCE.acquire().use {
      return GenZappDatabase.kyberPreKeys.get(selfServiceId, kyberPreKeyId)?.record ?: throw InvalidKeyIdException("Missing kyber prekey with ID: $kyberPreKeyId")
    }
  }

  override fun loadKyberPreKeys(): List<KyberPreKeyRecord> {
    ReentrantSessionLock.INSTANCE.acquire().use {
      return GenZappDatabase.kyberPreKeys.getAll(selfServiceId).map { it.record }
    }
  }

  override fun loadLastResortKyberPreKeys(): List<KyberPreKeyRecord> {
    ReentrantSessionLock.INSTANCE.acquire().use {
      return GenZappDatabase.kyberPreKeys.getAllLastResort(selfServiceId).map { it.record }
    }
  }

  override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
    ReentrantSessionLock.INSTANCE.acquire().use {
      return GenZappDatabase.kyberPreKeys.insert(selfServiceId, kyberPreKeyId, record, false)
    }
  }

  override fun storeLastResortKyberPreKey(kyberPreKeyId: Int, kyberPreKeyRecord: KyberPreKeyRecord) {
    ReentrantSessionLock.INSTANCE.acquire().use {
      return GenZappDatabase.kyberPreKeys.insert(selfServiceId, kyberPreKeyId, kyberPreKeyRecord, true)
    }
  }

  override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean {
    ReentrantSessionLock.INSTANCE.acquire().use {
      return GenZappDatabase.kyberPreKeys.contains(selfServiceId, kyberPreKeyId)
    }
  }

  override fun markKyberPreKeyUsed(kyberPreKeyId: Int) {
    ReentrantSessionLock.INSTANCE.acquire().use {
      GenZappDatabase.kyberPreKeys.deleteIfNotLastResort(selfServiceId, kyberPreKeyId)
    }
  }

  override fun removeKyberPreKey(kyberPreKeyId: Int) {
    ReentrantSessionLock.INSTANCE.acquire().use {
      GenZappDatabase.kyberPreKeys.delete(selfServiceId, kyberPreKeyId)
    }
  }

  override fun markAllOneTimeKyberPreKeysStaleIfNecessary(staleTime: Long) {
    ReentrantSessionLock.INSTANCE.acquire().use {
      GenZappDatabase.kyberPreKeys.markAllStaleIfNecessary(selfServiceId, staleTime)
    }
  }

  override fun deleteAllStaleOneTimeKyberPreKeys(threshold: Long, minCount: Int) {
    ReentrantSessionLock.INSTANCE.acquire().use {
      GenZappDatabase.kyberPreKeys.deleteAllStaleBefore(selfServiceId, threshold, minCount)
    }
  }
}
