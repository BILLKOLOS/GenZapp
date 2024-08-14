package org.thoughtcrime.securesms.messages.protocol

import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.whispersystems.GenZappservice.api.push.ServiceId

/**
 * The entry point for creating and retrieving buffered protocol stores.
 * These stores will read from disk, but never write, instead buffering the results in memory.
 * You can then call [flushToDisk] in order to write the buffered results to disk.
 *
 * This allows you to efficiently do batches of work and avoid unnecessary intermediate writes.
 */
class BufferedProtocolStore private constructor(
  private val aciStore: Pair<ServiceId, BufferedGenZappServiceAccountDataStore>,
  private val pniStore: Pair<ServiceId, BufferedGenZappServiceAccountDataStore>
) {

  fun get(serviceId: ServiceId): BufferedGenZappServiceAccountDataStore {
    return when (serviceId) {
      aciStore.first -> aciStore.second
      pniStore.first -> pniStore.second
      else -> error("No store matching serviceId $serviceId")
    }
  }

  fun getAciStore(): BufferedGenZappServiceAccountDataStore {
    return aciStore.second
  }

  /**
   * Writes any buffered data to disk. You can continue to use the same buffered store afterwards.
   */
  fun flushToDisk() {
    aciStore.second.flushToDisk(AppDependencies.protocolStore.aci())
    pniStore.second.flushToDisk(AppDependencies.protocolStore.pni())
  }

  companion object {
    fun create(): BufferedProtocolStore {
      val aci = GenZappStore.account.requireAci()
      val pni = GenZappStore.account.requirePni()

      return BufferedProtocolStore(
        aciStore = aci to BufferedGenZappServiceAccountDataStore(aci),
        pniStore = pni to BufferedGenZappServiceAccountDataStore(pni)
      )
    }
  }
}
