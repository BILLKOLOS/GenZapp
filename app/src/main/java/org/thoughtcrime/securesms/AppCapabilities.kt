package org.thoughtcrime.securesms

import org.whispersystems.GenZappservice.api.account.AccountAttributes

object AppCapabilities {
  /**
   * @param storageCapable Whether or not the user can use storage service. This is another way of
   * asking if the user has set a GenZapp PIN or not.
   */
  @JvmStatic
  fun getCapabilities(storageCapable: Boolean): AccountAttributes.Capabilities {
    return AccountAttributes.Capabilities(
      storage = storageCapable,
      deleteSync = true
    )
  }
}
