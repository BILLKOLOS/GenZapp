package org.thoughtcrime.securesms.crypto.storage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.GenZapp.core.util.logging.Log;
import org.GenZapp.libGenZapp.protocol.NoSessionException;
import org.GenZapp.libGenZapp.protocol.GenZappProtocolAddress;
import org.GenZapp.libGenZapp.protocol.state.SessionRecord;
import org.thoughtcrime.securesms.crypto.ReentrantSessionLock;
import org.thoughtcrime.securesms.database.SessionTable;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.whispersystems.GenZappservice.api.GenZappServiceSessionStore;
import org.whispersystems.GenZappservice.api.GenZappSessionLock;
import org.whispersystems.GenZappservice.api.push.ServiceId;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class TextSecureSessionStore implements GenZappServiceSessionStore {

  private static final String TAG = Log.tag(TextSecureSessionStore.class);

  private final ServiceId accountId;

  public TextSecureSessionStore(@NonNull ServiceId accountId) {
    this.accountId = accountId;
  }

  @Override
  public SessionRecord loadSession(@NonNull GenZappProtocolAddress address) {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      SessionRecord sessionRecord = GenZappDatabase.sessions().load(accountId, address);

      if (sessionRecord == null) {
        Log.w(TAG, "No existing session information found for " + address);
        return new SessionRecord();
      }

      return sessionRecord;
    }
  }

  @Override
  public List<SessionRecord> loadExistingSessions(List<GenZappProtocolAddress> addresses) throws NoSessionException {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      List<SessionRecord> sessionRecords = GenZappDatabase.sessions().load(accountId, addresses);

      if (sessionRecords.size() != addresses.size()) {
        String message = "Mismatch! Asked for " + addresses.size() + " sessions, but only found " + sessionRecords.size() + "!";
        Log.w(TAG, message);
        throw new NoSessionException(message);
      }

      if (sessionRecords.stream().anyMatch(Objects::isNull)) {
        throw new NoSessionException("Failed to find one or more sessions.");
      }

      return sessionRecords;
    }
  }

  @Override
  public void storeSession(@NonNull GenZappProtocolAddress address, @NonNull SessionRecord record) {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      GenZappDatabase.sessions().store(accountId, address, record);
    }
  }

  @Override
  public boolean containsSession(GenZappProtocolAddress address) {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      SessionRecord sessionRecord = GenZappDatabase.sessions().load(accountId, address);

      return sessionRecord != null && sessionRecord.hasSenderChain();
    }
  }

  @Override
  public void deleteSession(GenZappProtocolAddress address) {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      Log.w(TAG, "Deleting session for " + address);
      GenZappDatabase.sessions().delete(accountId, address);
    }
  }

  @Override
  public void deleteAllSessions(String name) {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      Log.w(TAG, "Deleting all sessions for " + name);
      GenZappDatabase.sessions().deleteAllFor(accountId, name);
    }
  }

  @Override
  public List<Integer> getSubDeviceSessions(String name) {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      return GenZappDatabase.sessions().getSubDevices(accountId, name);
    }
  }

  @Override
  public Map<GenZappProtocolAddress, SessionRecord> getAllAddressesWithActiveSessions(List<String> addressNames) {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      return GenZappDatabase.sessions()
                           .getAllFor(accountId, addressNames)
                           .stream()
                           .filter(row -> isActive(row.getRecord()))
                           .collect(Collectors.toMap(row -> new GenZappProtocolAddress(row.getAddress(), row.getDeviceId()), SessionTable.SessionRow::getRecord));
    }
  }

  @Override
  public void archiveSession(GenZappProtocolAddress address) {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      SessionRecord session = GenZappDatabase.sessions().load(accountId, address);
      if (session != null) {
        session.archiveCurrentState();
        GenZappDatabase.sessions().store(accountId, address, session);
      }
    }
  }
  
  public void archiveSession(@NonNull ServiceId serviceId, int deviceId) {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      archiveSession(new GenZappProtocolAddress(serviceId.toString(), deviceId));
    }
  }

  public void archiveSessions(@NonNull RecipientId recipientId, int deviceId) {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      Recipient recipient = Recipient.resolved(recipientId);

      if (recipient.getHasAci()) {
        archiveSession(new GenZappProtocolAddress(recipient.requireAci().toString(), deviceId));
      }

      if (recipient.getHasPni()) {
        archiveSession(new GenZappProtocolAddress(recipient.requirePni().toString(), deviceId));
      }

      if (recipient.getHasE164()) {
        archiveSession(new GenZappProtocolAddress(recipient.requireE164(), deviceId));
      }
    }
  }

  public void archiveSiblingSessions(@NonNull GenZappProtocolAddress address) {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      List<SessionTable.SessionRow> sessions = GenZappDatabase.sessions().getAllFor(accountId, address.getName());

      for (SessionTable.SessionRow row : sessions) {
        if (row.getDeviceId() != address.getDeviceId()) {
          row.getRecord().archiveCurrentState();
          storeSession(new GenZappProtocolAddress(row.getAddress(), row.getDeviceId()), row.getRecord());
        }
      }
    }
  }

  public void archiveAllSessions() {
    try (GenZappSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      List<SessionTable.SessionRow> sessions = GenZappDatabase.sessions().getAll(accountId);

      for (SessionTable.SessionRow row : sessions) {
        row.getRecord().archiveCurrentState();
        storeSession(new GenZappProtocolAddress(row.getAddress(), row.getDeviceId()), row.getRecord());
      }
    }
  }

  private static boolean isActive(@Nullable SessionRecord record) {
    return record != null && record.hasSenderChain();
  }
}
