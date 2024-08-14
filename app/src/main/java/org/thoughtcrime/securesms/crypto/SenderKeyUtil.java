package org.thoughtcrime.securesms.crypto;

import androidx.annotation.NonNull;

import org.GenZapp.libGenZapp.protocol.GenZappProtocolAddress;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.whispersystems.GenZappservice.api.GenZappSessionLock;
import org.whispersystems.GenZappservice.api.push.DistributionId;

public final class SenderKeyUtil {
  private SenderKeyUtil() {}

  /**
   * Clears the state for a sender key session we created. It will naturally get re-created when it is next needed, rotating the key.
   */
  public static void rotateOurKey(@NonNull DistributionId distributionId) {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      AppDependencies.getProtocolStore().aci().senderKeys().deleteAllFor(GenZappStore.account().requireAci().toString(), distributionId);
      GenZappDatabase.senderKeyShared().deleteAllFor(distributionId);
    }
  }

  /**
   * Gets when the sender key session was created, or -1 if it doesn't exist.
   */
  public static long getCreateTimeForOurKey(@NonNull DistributionId distributionId) {
    GenZappProtocolAddress address = new GenZappProtocolAddress(GenZappStore.account().requireAci().toString(), GenZappStore.account().getDeviceId());
    return GenZappDatabase.senderKeys().getCreatedTime(address, distributionId);
  }

  /**
   * Deletes all stored state around session keys. Should only really be used when the user is re-registering.
   */
  public static void clearAllState() {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      AppDependencies.getProtocolStore().aci().senderKeys().deleteAll();
      GenZappDatabase.senderKeyShared().deleteAll();
    }
  }
}
