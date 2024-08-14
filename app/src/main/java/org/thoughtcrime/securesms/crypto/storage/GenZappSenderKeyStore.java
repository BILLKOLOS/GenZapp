package org.thoughtcrime.securesms.crypto.storage;

import android.content.Context;

import androidx.annotation.NonNull;

import org.GenZapp.libGenZapp.protocol.GenZappProtocolAddress;
import org.GenZapp.libGenZapp.protocol.groups.state.SenderKeyRecord;
import org.thoughtcrime.securesms.crypto.ReentrantSessionLock;
import org.thoughtcrime.securesms.database.SenderKeyTable;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.whispersystems.GenZappservice.api.GenZappServiceSenderKeyStore;
import org.whispersystems.GenZappservice.api.GenZappSessionLock;
import org.whispersystems.GenZappservice.api.push.DistributionId;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

/**
 * An implementation of the storage interface used by the protocol layer to store sender keys. For
 * more details around sender keys, see {@link SenderKeyTable}.
 */
public final class GenZappSenderKeyStore implements GenZappServiceSenderKeyStore {

  private final Context context;

  public GenZappSenderKeyStore(@NonNull Context context) {
    this.context = context;
  }

  @Override
  public void storeSenderKey(@NonNull GenZappProtocolAddress sender, @NonNull UUID distributionId, @NonNull SenderKeyRecord record) {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      GenZappDatabase.senderKeys().store(sender, DistributionId.from(distributionId), record);
    }
  }

  @Override
  public @Nullable SenderKeyRecord loadSenderKey(@NonNull GenZappProtocolAddress sender, @NonNull UUID distributionId) {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      return GenZappDatabase.senderKeys().load(sender, DistributionId.from(distributionId));
    }
  }

  @Override
  public Set<GenZappProtocolAddress> getSenderKeySharedWith(DistributionId distributionId) {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      return GenZappDatabase.senderKeyShared().getSharedWith(distributionId);
    }
  }

  @Override
  public void markSenderKeySharedWith(DistributionId distributionId, Collection<GenZappProtocolAddress> addresses) {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      GenZappDatabase.senderKeyShared().markAsShared(distributionId, addresses);
    }
  }

  @Override
  public void clearSenderKeySharedWith(Collection<GenZappProtocolAddress> addresses) {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      GenZappDatabase.senderKeyShared().deleteAllFor(addresses);
    }
  }

  /**
   * Removes all sender key session state for all devices for the provided recipient-distributionId pair.
   */
  public void deleteAllFor(@NonNull String addressName, @NonNull DistributionId distributionId) {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      GenZappDatabase.senderKeys().deleteAllFor(addressName, distributionId);
    }
  }

  /**
   * Deletes all sender key session state.
   */
  public void deleteAll() {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      GenZappDatabase.senderKeys().deleteAll();
    }
  }
}