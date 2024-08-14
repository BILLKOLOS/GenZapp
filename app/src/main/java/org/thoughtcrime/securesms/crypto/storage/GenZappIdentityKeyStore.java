package org.thoughtcrime.securesms.crypto.storage;

import androidx.annotation.NonNull;

import org.GenZapp.libGenZapp.protocol.IdentityKey;
import org.GenZapp.libGenZapp.protocol.IdentityKeyPair;
import org.GenZapp.libGenZapp.protocol.GenZappProtocolAddress;
import org.GenZapp.libGenZapp.protocol.state.IdentityKeyStore;
import org.thoughtcrime.securesms.database.IdentityTable.VerifiedStatus;
import org.thoughtcrime.securesms.database.identity.IdentityRecordList;
import org.thoughtcrime.securesms.database.model.IdentityRecord;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.whispersystems.GenZappservice.api.push.ServiceId;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A wrapper around an instance of {@link GenZappBaseIdentityKeyStore} that lets us report different values for {@link #getIdentityKeyPair()}.
 * This lets us have multiple instances (one for ACI, one for PNI) that share the same underlying data while also reporting the correct identity key.
 */
public class GenZappIdentityKeyStore implements IdentityKeyStore {

  private final GenZappBaseIdentityKeyStore baseStore;
  private final Supplier<IdentityKeyPair>  identitySupplier;

  public GenZappIdentityKeyStore(@NonNull GenZappBaseIdentityKeyStore baseStore, @NonNull Supplier<IdentityKeyPair> identitySupplier) {
    this.baseStore        = baseStore;
    this.identitySupplier = identitySupplier;
  }

  @Override
  public IdentityKeyPair getIdentityKeyPair() {
    return identitySupplier.get();
  }

  @Override
  public int getLocalRegistrationId() {
    return baseStore.getLocalRegistrationId();
  }

  @Override
  public boolean saveIdentity(GenZappProtocolAddress address, IdentityKey identityKey) {
    return baseStore.saveIdentity(address, identityKey);
  }

  public @NonNull SaveResult saveIdentity(GenZappProtocolAddress address, IdentityKey identityKey, boolean nonBlockingApproval) {
    return baseStore.saveIdentity(address, identityKey, nonBlockingApproval);
  }

  public void saveIdentityWithoutSideEffects(@NonNull RecipientId recipientId,
                                             @NonNull ServiceId serviceId,
                                             IdentityKey identityKey,
                                             VerifiedStatus verifiedStatus,
                                             boolean firstUse,
                                             long timestamp,
                                             boolean nonBlockingApproval)
  {
    baseStore.saveIdentityWithoutSideEffects(recipientId, serviceId, identityKey, verifiedStatus, firstUse, timestamp, nonBlockingApproval);
  }

  @Override
  public boolean isTrustedIdentity(GenZappProtocolAddress address, IdentityKey identityKey, Direction direction) {
    return baseStore.isTrustedIdentity(address, identityKey, direction);
  }

  @Override
  public IdentityKey getIdentity(GenZappProtocolAddress address) {
    return baseStore.getIdentity(address);
  }

  public @NonNull Optional<IdentityRecord> getIdentityRecord(@NonNull RecipientId recipientId) {
    return baseStore.getIdentityRecord(recipientId);
  }

  public @NonNull IdentityRecordList getIdentityRecords(@NonNull List<Recipient> recipients) {
    return baseStore.getIdentityRecords(recipients);
  }

  public void setApproval(@NonNull RecipientId recipientId, boolean nonBlockingApproval) {
    baseStore.setApproval(recipientId, nonBlockingApproval);
  }

  public void setVerified(@NonNull RecipientId recipientId, IdentityKey identityKey, VerifiedStatus verifiedStatus) {
    baseStore.setVerified(recipientId, identityKey, verifiedStatus);
  }

  public void delete(@NonNull String addressName) {
    baseStore.delete(addressName);
  }

  public void invalidate(@NonNull String addressName) {
    baseStore.invalidate(addressName);
  }

  public enum SaveResult {
    NEW,
    UPDATE,
    NON_BLOCKING_APPROVAL_REQUIRED,
    NO_CHANGE
  }
}
