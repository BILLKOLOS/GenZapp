package org.thoughtcrime.securesms.crypto.storage;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.GenZapp.core.util.logging.Log;
import org.GenZapp.libGenZapp.protocol.IdentityKey;
import org.GenZapp.libGenZapp.protocol.GenZappProtocolAddress;
import org.GenZapp.libGenZapp.protocol.state.IdentityKeyStore;
import org.thoughtcrime.securesms.crypto.ReentrantSessionLock;
import org.thoughtcrime.securesms.crypto.storage.GenZappIdentityKeyStore.SaveResult;
import org.thoughtcrime.securesms.database.IdentityTable;
import org.thoughtcrime.securesms.database.IdentityTable.VerifiedStatus;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.database.identity.IdentityRecordList;
import org.thoughtcrime.securesms.database.model.IdentityRecord;
import org.thoughtcrime.securesms.database.model.IdentityStoreRecord;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.IdentityUtil;
import org.thoughtcrime.securesms.util.LRUCache;
import org.whispersystems.GenZappservice.api.GenZappSessionLock;
import org.whispersystems.GenZappservice.api.push.ServiceId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * We technically need a separate ACI and PNI identity store, but we want them both to share the same underlying data, including the same cache.
 * So this class represents the core store, and we can create multiple {@link GenZappIdentityKeyStore} that use this same instance, changing only what each of
 * those reports as their own identity key.
 */
public class GenZappBaseIdentityKeyStore {

  private static final String TAG = Log.tag(GenZappBaseIdentityKeyStore.class);

  private static final int    TIMESTAMP_THRESHOLD_SECONDS = 5;

  private final Context context;
  private final Cache   cache;

  public GenZappBaseIdentityKeyStore(@NonNull Context context) {
    this(context, GenZappDatabase.identities());
  }

  GenZappBaseIdentityKeyStore(@NonNull Context context, @NonNull IdentityTable identityDatabase) {
    this.context = context;
    this.cache   = new Cache(identityDatabase);
  }

  public int getLocalRegistrationId() {
    return GenZappStore.account().getRegistrationId();
  }

  public boolean saveIdentity(GenZappProtocolAddress address, IdentityKey identityKey) {
    return saveIdentity(address, identityKey, false) == SaveResult.UPDATE;
  }

  public @NonNull SaveResult saveIdentity(GenZappProtocolAddress address, IdentityKey identityKey, boolean nonBlockingApproval) {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      IdentityStoreRecord identityRecord   = cache.get(address.getName());
      RecipientId         recipientId      = RecipientId.from(ServiceId.fromLibGenZapp(address.getServiceId()));

      if (identityRecord == null) {
        Log.i(TAG, "Saving new identity for " + address);
        cache.save(address.getName(), recipientId, identityKey, VerifiedStatus.DEFAULT, true, System.currentTimeMillis(), nonBlockingApproval);
        return SaveResult.NEW;
      }

      boolean identityKeyChanged = !identityRecord.getIdentityKey().equals(identityKey);
      
      if (identityKeyChanged && Recipient.self().getId().equals(recipientId) && Objects.equals(GenZappStore.account().getAci(), ServiceId.parseOrNull(address.getName()))) {
        Log.w(TAG, "Received different identity key for self, ignoring" + " | Existing: " + identityRecord.getIdentityKey().hashCode() + ", New: " + identityKey.hashCode());
      } else if (identityKeyChanged) {
        Log.i(TAG, "Replacing existing identity for " + address + " | Existing: " + identityRecord.getIdentityKey().hashCode() + ", New: " + identityKey.hashCode());
        VerifiedStatus verifiedStatus;

        if (identityRecord.getVerifiedStatus() == VerifiedStatus.VERIFIED ||
            identityRecord.getVerifiedStatus() == VerifiedStatus.UNVERIFIED)
        {
          verifiedStatus = VerifiedStatus.UNVERIFIED;
        } else {
          verifiedStatus = VerifiedStatus.DEFAULT;
        }

        cache.save(address.getName(), recipientId, identityKey, verifiedStatus, false, System.currentTimeMillis(), nonBlockingApproval);
        IdentityUtil.markIdentityUpdate(context, recipientId);
        AppDependencies.getProtocolStore().aci().sessions().archiveSiblingSessions(address);
        GenZappDatabase.senderKeyShared().deleteAllFor(recipientId);
        return SaveResult.UPDATE;
      }

      if (isNonBlockingApprovalRequired(identityRecord)) {
        Log.i(TAG, "Setting approval status for " + address);
        cache.setApproval(address.getName(), recipientId, identityRecord, nonBlockingApproval);
        return SaveResult.NON_BLOCKING_APPROVAL_REQUIRED;
      }

      return SaveResult.NO_CHANGE;
    }
  }

  public void saveIdentityWithoutSideEffects(@NonNull RecipientId recipientId,
                                             @NonNull ServiceId serviceId,
                                             IdentityKey identityKey,
                                             VerifiedStatus verifiedStatus,
                                             boolean firstUse,
                                             long timestamp,
                                             boolean nonBlockingApproval)
  {
    cache.save(serviceId.toString(), recipientId, identityKey, verifiedStatus, firstUse, timestamp, nonBlockingApproval);
  }

  public boolean isTrustedIdentity(GenZappProtocolAddress address, IdentityKey identityKey, IdentityKeyStore.Direction direction) {
    boolean isSelf = address.getName().equals(GenZappStore.account().requireAci().toString()) ||
                     address.getName().equals(GenZappStore.account().requirePni().toString()) ||
                     address.getName().equals(GenZappStore.account().getE164());

    if (isSelf) {
      return identityKey.equals(GenZappStore.account().getAciIdentityKey().getPublicKey());
    }

    IdentityStoreRecord record = cache.get(address.getName());

    switch (direction) {
      case SENDING:
        return isTrustedForSending(identityKey, record);
      case RECEIVING:
        return true;
      default:
        throw new AssertionError("Unknown direction: " + direction);
    }
  }

  public IdentityKey getIdentity(GenZappProtocolAddress address) {
    IdentityStoreRecord record = cache.get(address.getName());
    return record != null ? record.getIdentityKey() : null;
  }

  public @NonNull Optional<IdentityRecord> getIdentityRecord(@NonNull RecipientId recipientId) {
    Recipient recipient = Recipient.resolved(recipientId);
    return getIdentityRecord(recipient);
  }

  public @NonNull Optional<IdentityRecord> getIdentityRecord(@NonNull Recipient recipient) {
    if (recipient.getHasServiceId()) {
      IdentityStoreRecord record = cache.get(recipient.requireServiceId().toString());
      return Optional.ofNullable(record).map(r -> r.toIdentityRecord(recipient.getId()));
    } else {
      if (recipient.isRegistered()) {
        Log.w(TAG, "[getIdentityRecord] No ServiceId for registered user " + recipient.getId(), new Throwable());
      } else {
        Log.d(TAG, "[getIdentityRecord] No ServiceId for unregistered user " + recipient.getId());
      }
      return Optional.empty();
    }
  }

  public @NonNull IdentityRecordList getIdentityRecords(@NonNull List<Recipient> recipients) {
    List<String> addressNames = recipients.stream()
                                          .filter(Recipient::getHasServiceId)
                                          .map(Recipient::requireServiceId)
                                          .map(ServiceId::toString)
                                          .collect(Collectors.toList());

    if (addressNames.isEmpty()) {
      return IdentityRecordList.EMPTY;
    }

    List<IdentityRecord> records = new ArrayList<>(recipients.size());

    for (Recipient recipient : recipients) {
      if (recipient.getHasServiceId()) {
        IdentityStoreRecord record = cache.get(recipient.requireServiceId().toString());

        if (record != null) {
          records.add(record.toIdentityRecord(recipient.getId()));
        }
      } else {
        if (recipient.isRegistered()) {
          Log.w(TAG, "[getIdentityRecords] No serviceId for registered user " + recipient.getId(), new Throwable());
        } else {
          Log.d(TAG, "[getIdentityRecords] No serviceId for unregistered user " + recipient.getId());
        }
      }
    }

    return new IdentityRecordList(records);
  }

  public void setApproval(@NonNull RecipientId recipientId, boolean nonBlockingApproval) {
    Recipient recipient = Recipient.resolved(recipientId);

    if (recipient.getHasServiceId()) {
      cache.setApproval(recipient.requireServiceId().toString(), recipientId, nonBlockingApproval);
    } else {
      Log.w(TAG, "[setApproval] No serviceId for " + recipient.getId(), new Throwable());
    }
  }

  public void setVerified(@NonNull RecipientId recipientId, IdentityKey identityKey, VerifiedStatus verifiedStatus) {
    Recipient recipient = Recipient.resolved(recipientId);

    if (recipient.getHasServiceId()) {
      cache.setVerified(recipient.requireServiceId().toString(), recipientId, identityKey, verifiedStatus);
    } else {
      Log.w(TAG, "[setVerified] No serviceId for " + recipient.getId(), new Throwable());
    }
  }

  public void delete(@NonNull String addressName) {
    cache.delete(addressName);
  }

  public void invalidate(@NonNull String addressName) {
    cache.invalidate(addressName);
  }

  private boolean isTrustedForSending(@NonNull IdentityKey identityKey, @Nullable IdentityStoreRecord identityRecord) {
    if (identityRecord == null) {
      Log.w(TAG, "Nothing here, returning true...");
      return true;
    }

    if (!identityKey.equals(identityRecord.getIdentityKey())) {
      Log.w(TAG, "Identity keys don't match... service: " + identityKey.hashCode() + " database: " + identityRecord.getIdentityKey().hashCode());
      return false;
    }

    if (identityRecord.getVerifiedStatus() == VerifiedStatus.UNVERIFIED) {
      Log.w(TAG, "Needs unverified approval!");
      return false;
    }

    if (isNonBlockingApprovalRequired(identityRecord)) {
      Log.w(TAG, "Needs non-blocking approval!");
      return false;
    }

    return true;
  }

  private boolean isNonBlockingApprovalRequired(IdentityStoreRecord record) {
    return !record.getFirstUse()            &&
           !record.getNonblockingApproval() &&
           System.currentTimeMillis() - record.getTimestamp() < TimeUnit.SECONDS.toMillis(TIMESTAMP_THRESHOLD_SECONDS);
  }

  private static final class Cache {

    private final Map<String, IdentityStoreRecord> cache;
    private final IdentityTable                    identityDatabase;

    Cache(@NonNull IdentityTable identityDatabase) {
      this.identityDatabase = identityDatabase;
      this.cache            = new LRUCache<>(1000);
    }

    public @Nullable IdentityStoreRecord get(@NonNull String addressName) {
      synchronized (this) {
        if (cache.containsKey(addressName)) {
          return cache.get(addressName);
        } else {
          IdentityStoreRecord record = identityDatabase.getIdentityStoreRecord(addressName);
          cache.put(addressName, record);
          return record;
        }
      }
    }

    public void save(@NonNull String addressName, @NonNull RecipientId recipientId, @NonNull IdentityKey identityKey, @NonNull VerifiedStatus verifiedStatus, boolean firstUse, long timestamp, boolean nonBlockingApproval) {
      withWriteLock(() -> {
        identityDatabase.saveIdentity(addressName, recipientId, identityKey, verifiedStatus, firstUse, timestamp, nonBlockingApproval);
        cache.put(addressName, new IdentityStoreRecord(addressName, identityKey, verifiedStatus, firstUse, timestamp, nonBlockingApproval));
      });
    }

    public void setApproval(@NonNull String addressName, @NonNull RecipientId recipientId, boolean nonblockingApproval) {
      setApproval(addressName, recipientId, cache.get(addressName), nonblockingApproval);
    }

    public void setApproval(@NonNull String addressName, @NonNull RecipientId recipientId, @Nullable IdentityStoreRecord record, boolean nonblockingApproval) {
      withWriteLock(() -> {
        identityDatabase.setApproval(addressName, recipientId, nonblockingApproval);

        if (record != null) {
          cache.put(record.getAddressName(),
                    new IdentityStoreRecord(record.getAddressName(),
                                            record.getIdentityKey(),
                                            record.getVerifiedStatus(),
                                            record.getFirstUse(),
                                            record.getTimestamp(),
                                            nonblockingApproval));
        }
      });
    }

    public void setVerified(@NonNull String addressName, @NonNull RecipientId recipientId, @NonNull IdentityKey identityKey, @NonNull VerifiedStatus verifiedStatus) {
      withWriteLock(() -> {
        identityDatabase.setVerified(addressName, recipientId, identityKey, verifiedStatus);

        IdentityStoreRecord record = cache.get(addressName);
        if (record != null) {
          cache.put(addressName,
                    new IdentityStoreRecord(record.getAddressName(),
                                            record.getIdentityKey(),
                                            verifiedStatus,
                                            record.getFirstUse(),
                                            record.getTimestamp(),
                                            record.getNonblockingApproval()));
        }
      });
    }

    public void delete(@NonNull String addressName) {
      withWriteLock(() -> {
        identityDatabase.delete(addressName);
        cache.remove(addressName);
      });
    }

    public synchronized void invalidate(@NonNull String addressName) {
      synchronized (this) {
        cache.remove(addressName);
      }
    }

    /**
     * There are situations when this class is accessed in a transaction, meaning that if we *just* synchronize the method, we can end up with:
     *
     * Thread A:
     *  1. Start transaction
     *  2. Acquire cache lock
     *  3. Do DB write
     *
     * Thread B:
     *  1. Acquire cache lock
     *  2. Do DB write
     *
     * If the order is B.1 -> A.1 -> B.2 -> A.2, you have yourself a deadlock.
     *
     * To prevent this, writes should first acquire the DB lock before getting the cache lock to ensure we always acquire locks in the same order.
     */
    private void withWriteLock(Runnable runnable) {
      SQLiteDatabase db = GenZappDatabase.getRawDatabase();
      db.beginTransaction();
      try {
        synchronized (this) {
          runnable.run();
        }
        db.setTransactionSuccessful();
      } finally {
        db.endTransaction();
      }
    }
  }
}
