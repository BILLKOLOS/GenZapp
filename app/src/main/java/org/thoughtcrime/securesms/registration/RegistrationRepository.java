package org.thoughtcrime.securesms.registration;

import android.app.Application;
import android.app.backup.BackupManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.app.NotificationManagerCompat;

import org.GenZapp.core.util.logging.Log;
import org.GenZapp.libGenZapp.protocol.IdentityKeyPair;
import org.GenZapp.libGenZapp.protocol.state.KyberPreKeyRecord;
import org.GenZapp.libGenZapp.protocol.state.SignedPreKeyRecord;
import org.GenZapp.libGenZapp.protocol.util.KeyHelper;
import org.GenZapp.libGenZapp.zkgroup.profiles.ProfileKey;
import org.thoughtcrime.securesms.crypto.PreKeyUtil;
import org.thoughtcrime.securesms.crypto.ProfileKeyUtil;
import org.thoughtcrime.securesms.crypto.SenderKeyUtil;
import org.thoughtcrime.securesms.crypto.storage.PreKeyMetadataStore;
import org.thoughtcrime.securesms.crypto.storage.GenZappServiceAccountDataStoreImpl;
import org.thoughtcrime.securesms.database.IdentityTable;
import org.thoughtcrime.securesms.database.RecipientTable;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobs.DirectoryRefreshJob;
import org.thoughtcrime.securesms.jobs.PreKeysSyncJob;
import org.thoughtcrime.securesms.jobs.RotateCertificateJob;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.notifications.NotificationIds;
import org.thoughtcrime.securesms.pin.SvrRepository;
import org.thoughtcrime.securesms.push.AccountManagerFactory;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.service.DirectoryRefreshListener;
import org.thoughtcrime.securesms.service.RotateSignedPreKeyListener;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.GenZappservice.api.GenZappServiceAccountManager;
import org.whispersystems.GenZappservice.api.account.PreKeyCollection;
import org.whispersystems.GenZappservice.api.push.ServiceId.ACI;
import org.whispersystems.GenZappservice.api.push.ServiceId.PNI;
import org.whispersystems.GenZappservice.api.push.ServiceId;
import org.whispersystems.GenZappservice.api.push.GenZappServiceAddress;
import org.whispersystems.GenZappservice.api.util.Preconditions;
import org.whispersystems.GenZappservice.internal.ServiceResponse;
import org.whispersystems.GenZappservice.internal.push.BackupAuthCheckProcessor;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Operations required for finalizing the registration of an account. This is
 * to be used after verifying the code and registration lock (if necessary) with
 * the server and being issued a UUID.
 */
public final class RegistrationRepository {

  private static final String TAG = Log.tag(RegistrationRepository.class);

  private final Application context;

  public RegistrationRepository(@NonNull Application context) {
    this.context = context;
  }

  public int getRegistrationId() {
    int registrationId = GenZappStore.account().getRegistrationId();
    if (registrationId == 0) {
      registrationId = KeyHelper.generateRegistrationId(false);
      GenZappStore.account().setRegistrationId(registrationId);
    }
    return registrationId;
  }

  public int getPniRegistrationId() {
    int pniRegistrationId = GenZappStore.account().getPniRegistrationId();
    if (pniRegistrationId == 0) {
      pniRegistrationId = KeyHelper.generateRegistrationId(false);
      GenZappStore.account().setPniRegistrationId(pniRegistrationId);
    }
    return pniRegistrationId;
  }

  public @NonNull ProfileKey getProfileKey(@NonNull String e164) {
    ProfileKey profileKey = findExistingProfileKey(e164);

    if (profileKey == null) {
      profileKey = ProfileKeyUtil.createNew();
      Log.i(TAG, "No profile key found, created a new one");
    }

    return profileKey;
  }

  public Single<ServiceResponse<VerifyResponse>> registerAccount(@NonNull RegistrationData registrationData,
                                                                 @NonNull VerifyResponse response,
                                                                 boolean setRegistrationLockEnabled)
  {
    return Single.<ServiceResponse<VerifyResponse>>fromCallable(() -> {
      try {
        registerAccountInternal(registrationData, response, setRegistrationLockEnabled);

        JobManager jobManager = AppDependencies.getJobManager();
        jobManager.add(new DirectoryRefreshJob(false));
        jobManager.add(new RotateCertificateJob());

        DirectoryRefreshListener.schedule(context);
        RotateSignedPreKeyListener.schedule(context);

        return ServiceResponse.forResult(response, 200, null);
      } catch (IOException e) {
        return ServiceResponse.forUnknownError(e);
      }
    }).subscribeOn(Schedulers.io());
  }

  @WorkerThread
  private void registerAccountInternal(@NonNull RegistrationData registrationData,
                                       @NonNull VerifyResponse response,
                                       boolean setRegistrationLockEnabled)
      throws IOException
  {
    Preconditions.checkNotNull(response.getAciPreKeyCollection(), "Missing ACI prekey collection!");
    Preconditions.checkNotNull(response.getPniPreKeyCollection(), "Missing PNI prekey collection!");

    ACI     aci    = ACI.parseOrThrow(response.getVerifyAccountResponse().getUuid());
    PNI     pni    = PNI.parseOrThrow(response.getVerifyAccountResponse().getPni());
    boolean hasPin = response.getVerifyAccountResponse().isStorageCapable();

    GenZappStore.account().setAci(aci);
    GenZappStore.account().setPni(pni);

    AppDependencies.resetProtocolStores();

    AppDependencies.getProtocolStore().aci().sessions().archiveAllSessions();
    AppDependencies.getProtocolStore().pni().sessions().archiveAllSessions();
    SenderKeyUtil.clearAllState();

    GenZappServiceAccountDataStoreImpl aciProtocolStore = AppDependencies.getProtocolStore().aci();
    PreKeyMetadataStore               aciMetadataStore = GenZappStore.account().aciPreKeys();

    GenZappServiceAccountDataStoreImpl pniProtocolStore = AppDependencies.getProtocolStore().pni();
    PreKeyMetadataStore               pniMetadataStore = GenZappStore.account().pniPreKeys();

    storeSignedAndLastResortPreKeys(aciProtocolStore, aciMetadataStore, response.getAciPreKeyCollection());
    storeSignedAndLastResortPreKeys(pniProtocolStore, pniMetadataStore, response.getPniPreKeyCollection());

    RecipientTable recipientTable = GenZappDatabase.recipients();
    RecipientId    selfId         = Recipient.trustedPush(aci, pni, registrationData.getE164()).getId();

    recipientTable.setProfileSharing(selfId, true);
    recipientTable.markRegisteredOrThrow(selfId, aci);
    recipientTable.linkIdsForSelf(aci, pni, registrationData.getE164());
    recipientTable.setProfileKey(selfId, registrationData.getProfileKey());

    AppDependencies.getRecipientCache().clearSelf();

    GenZappStore.account().setE164(registrationData.getE164());
    GenZappStore.account().setFcmToken(registrationData.getFcmToken());
    GenZappStore.account().setFcmEnabled(registrationData.isFcm());

    long now = System.currentTimeMillis();
    saveOwnIdentityKey(selfId, aci, aciProtocolStore, now);
    saveOwnIdentityKey(selfId, pni, pniProtocolStore, now);

    GenZappStore.account().setServicePassword(registrationData.getPassword());
    GenZappStore.account().setRegistered(true);
    TextSecurePreferences.setPromptedPushRegistration(context, true);
    TextSecurePreferences.setUnauthorizedReceived(context, false);
    NotificationManagerCompat.from(context).cancel(NotificationIds.UNREGISTERED_NOTIFICATION_ID);

    SvrRepository.onRegistrationComplete(response.getMasterKey(), response.getPin(), hasPin, setRegistrationLockEnabled);

    AppDependencies.resetNetwork();
    AppDependencies.getIncomingMessageObserver();
    PreKeysSyncJob.enqueue();
  }

  public static PreKeyCollection generateSignedAndLastResortPreKeys(IdentityKeyPair identity, PreKeyMetadataStore metadataStore) {
    SignedPreKeyRecord      signedPreKey          = PreKeyUtil.generateSignedPreKey(metadataStore.getNextSignedPreKeyId(), identity.getPrivateKey());
    KyberPreKeyRecord       lastResortKyberPreKey = PreKeyUtil.generateLastResortKyberPreKey(metadataStore.getNextKyberPreKeyId(), identity.getPrivateKey());

    return new PreKeyCollection(
        identity.getPublicKey(),
        signedPreKey,
        lastResortKyberPreKey
    );
  }

  private static void storeSignedAndLastResortPreKeys(GenZappServiceAccountDataStoreImpl protocolStore, PreKeyMetadataStore metadataStore, PreKeyCollection preKeyCollection) {
    PreKeyUtil.storeSignedPreKey(protocolStore, metadataStore, preKeyCollection.getSignedPreKey());
    metadataStore.setSignedPreKeyRegistered(true);
    metadataStore.setActiveSignedPreKeyId(preKeyCollection.getSignedPreKey().getId());
    metadataStore.setLastSignedPreKeyRotationTime(System.currentTimeMillis());

    PreKeyUtil.storeLastResortKyberPreKey(protocolStore, metadataStore, preKeyCollection.getLastResortKyberPreKey());
    metadataStore.setLastResortKyberPreKeyId(preKeyCollection.getLastResortKyberPreKey().getId());
    metadataStore.setLastResortKyberPreKeyRotationTime(System.currentTimeMillis());
  }

  private void saveOwnIdentityKey(@NonNull RecipientId selfId, @NonNull ServiceId serviceId, @NonNull GenZappServiceAccountDataStoreImpl protocolStore, long now) {
    protocolStore.identities().saveIdentityWithoutSideEffects(selfId,
                                                              serviceId,
                                                              protocolStore.getIdentityKeyPair().getPublicKey(),
                                                              IdentityTable.VerifiedStatus.VERIFIED,
                                                              true,
                                                              now,
                                                              true);
  }

  @WorkerThread
  private static @Nullable ProfileKey findExistingProfileKey(@NonNull String e164number) {
    RecipientTable        recipientTable = GenZappDatabase.recipients();
    Optional<RecipientId> recipient      = recipientTable.getByE164(e164number);

    if (recipient.isPresent()) {
      return ProfileKeyUtil.profileKeyOrNull(Recipient.resolved(recipient.get()).getProfileKey());
    }

    return null;
  }

  public Single<BackupAuthCheckProcessor> getSvrAuthCredential(@NonNull RegistrationData registrationData, List<String> usernamePasswords) {
    GenZappServiceAccountManager accountManager = AccountManagerFactory.getInstance().createUnauthenticated(context, registrationData.getE164(), GenZappServiceAddress.DEFAULT_DEVICE_ID, registrationData.getPassword());

    Log.d(TAG, "Fetching SVR backup credentials.");
    return accountManager.checkBackupAuthCredentials(registrationData.getE164(), usernamePasswords)
                         .map(BackupAuthCheckProcessor::new)
                         .doOnSuccess(processor -> {
                           Log.d(TAG, "Received SVR backup auth credential response.");
                           if (GenZappStore.svr().removeSvr2AuthTokens(processor.getInvalid())) {
                             new BackupManager(context).dataChanged();
                           }
                         });
  }

}
