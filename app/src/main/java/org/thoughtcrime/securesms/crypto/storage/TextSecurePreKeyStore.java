package org.thoughtcrime.securesms.crypto.storage;

import androidx.annotation.NonNull;

import org.GenZapp.core.util.logging.Log;
import org.GenZapp.libGenZapp.protocol.InvalidKeyIdException;
import org.GenZapp.libGenZapp.protocol.state.PreKeyRecord;
import org.GenZapp.libGenZapp.protocol.state.SignedPreKeyRecord;
import org.GenZapp.libGenZapp.protocol.state.SignedPreKeyStore;
import org.thoughtcrime.securesms.crypto.ReentrantSessionLock;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.whispersystems.GenZappservice.api.GenZappServicePreKeyStore;
import org.whispersystems.GenZappservice.api.GenZappSessionLock;
import org.whispersystems.GenZappservice.api.push.ServiceId;

import java.util.List;

public class TextSecurePreKeyStore implements GenZappServicePreKeyStore, SignedPreKeyStore {

  @SuppressWarnings("unused")
  private static final String TAG = Log.tag(TextSecurePreKeyStore.class);

  @NonNull
  private final ServiceId accountId;

  public TextSecurePreKeyStore(@NonNull ServiceId accountId) {
    this.accountId = accountId;
  }

  @Override
  public PreKeyRecord loadPreKey(int preKeyId) throws InvalidKeyIdException {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      PreKeyRecord preKeyRecord = GenZappDatabase.oneTimePreKeys().get(accountId, preKeyId);

      if (preKeyRecord == null) throw new InvalidKeyIdException("No such key: " + preKeyId);
      else                      return preKeyRecord;
    }
  }

  @Override
  public SignedPreKeyRecord loadSignedPreKey(int signedPreKeyId) throws InvalidKeyIdException {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      SignedPreKeyRecord signedPreKeyRecord = GenZappDatabase.signedPreKeys().get(accountId, signedPreKeyId);

      if (signedPreKeyRecord == null) throw new InvalidKeyIdException("No such signed prekey: " + signedPreKeyId);
      else                            return signedPreKeyRecord;
    }
  }

  @Override
  public List<SignedPreKeyRecord> loadSignedPreKeys() {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      return GenZappDatabase.signedPreKeys().getAll(accountId);
    }
  }

  @Override
  public void storePreKey(int preKeyId, PreKeyRecord record) {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      GenZappDatabase.oneTimePreKeys().insert(accountId, preKeyId, record);
    }
  }

  @Override
  public void storeSignedPreKey(int signedPreKeyId, SignedPreKeyRecord record) {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      GenZappDatabase.signedPreKeys().insert(accountId, signedPreKeyId, record);
    }
  }

  @Override
  public boolean containsPreKey(int preKeyId) {
    return GenZappDatabase.oneTimePreKeys().get(accountId, preKeyId) != null;
  }

  @Override
  public boolean containsSignedPreKey(int signedPreKeyId) {
    return GenZappDatabase.signedPreKeys().get(accountId, signedPreKeyId) != null;
  }

  @Override
  public void removePreKey(int preKeyId) {
    GenZappDatabase.oneTimePreKeys().delete(accountId, preKeyId);
  }

  @Override
  public void removeSignedPreKey(int signedPreKeyId) {
    GenZappDatabase.signedPreKeys().delete(accountId, signedPreKeyId);
  }

  @Override
  public void markAllOneTimeEcPreKeysStaleIfNecessary(long staleTime) {
    GenZappDatabase.oneTimePreKeys().markAllStaleIfNecessary(accountId, staleTime);
  }

  @Override
  public void deleteAllStaleOneTimeEcPreKeys(long threshold, int minCount) {
    GenZappDatabase.oneTimePreKeys().deleteAllStaleBefore(accountId, threshold, minCount);
  }
}
