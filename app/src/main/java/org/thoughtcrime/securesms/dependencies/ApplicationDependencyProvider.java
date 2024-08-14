package org.thoughtcrime.securesms.dependencies;

import android.annotation.SuppressLint;
import android.app.Application;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import org.GenZapp.core.util.ThreadUtil;
import org.GenZapp.core.util.concurrent.DeadlockDetector;
import org.GenZapp.core.util.concurrent.GenZappExecutors;
import org.GenZapp.libGenZapp.net.Network;
import org.GenZapp.libGenZapp.zkgroup.profiles.ClientZkProfileOperations;
import org.GenZapp.libGenZapp.zkgroup.receipts.ClientZkReceiptOperations;
import org.thoughtcrime.securesms.BuildConfig;
import org.thoughtcrime.securesms.components.TypingStatusRepository;
import org.thoughtcrime.securesms.components.TypingStatusSender;
import org.thoughtcrime.securesms.crypto.ReentrantSessionLock;
import org.thoughtcrime.securesms.crypto.storage.GenZappBaseIdentityKeyStore;
import org.thoughtcrime.securesms.crypto.storage.GenZappIdentityKeyStore;
import org.thoughtcrime.securesms.crypto.storage.GenZappKyberPreKeyStore;
import org.thoughtcrime.securesms.crypto.storage.GenZappSenderKeyStore;
import org.thoughtcrime.securesms.crypto.storage.GenZappServiceAccountDataStoreImpl;
import org.thoughtcrime.securesms.crypto.storage.GenZappServiceDataStoreImpl;
import org.thoughtcrime.securesms.crypto.storage.TextSecurePreKeyStore;
import org.thoughtcrime.securesms.crypto.storage.TextSecureSessionStore;
import org.thoughtcrime.securesms.database.DatabaseObserver;
import org.thoughtcrime.securesms.database.JobDatabase;
import org.thoughtcrime.securesms.database.PendingRetryReceiptCache;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobmanager.JobMigrator;
import org.thoughtcrime.securesms.jobmanager.impl.FactoryJobPredicate;
import org.thoughtcrime.securesms.jobs.FastJobStorage;
import org.thoughtcrime.securesms.jobs.GroupCallUpdateSendJob;
import org.thoughtcrime.securesms.jobs.IndividualSendJob;
import org.thoughtcrime.securesms.jobs.JobManagerFactories;
import org.thoughtcrime.securesms.jobs.MarkerJob;
import org.thoughtcrime.securesms.jobs.PreKeysSyncJob;
import org.thoughtcrime.securesms.jobs.PushGroupSendJob;
import org.thoughtcrime.securesms.jobs.PushProcessMessageJob;
import org.thoughtcrime.securesms.jobs.ReactionSendJob;
import org.thoughtcrime.securesms.jobs.TypingSendJob;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.megaphone.MegaphoneRepository;
import org.thoughtcrime.securesms.messages.IncomingMessageObserver;
import org.thoughtcrime.securesms.net.DefaultWebSocketShadowingBridge;
import org.thoughtcrime.securesms.net.GenZappWebSocketHealthMonitor;
import org.thoughtcrime.securesms.net.StandardUserAgentInterceptor;
import org.thoughtcrime.securesms.notifications.MessageNotifier;
import org.thoughtcrime.securesms.notifications.OptimizedMessageNotifier;
import org.thoughtcrime.securesms.payments.MobileCoinConfig;
import org.thoughtcrime.securesms.payments.Payments;
import org.thoughtcrime.securesms.push.SecurityEventListener;
import org.thoughtcrime.securesms.push.GenZappServiceNetworkAccess;
import org.thoughtcrime.securesms.recipients.LiveRecipientCache;
import org.thoughtcrime.securesms.revealable.ViewOnceMessageManager;
import org.thoughtcrime.securesms.service.DeletedCallEventManager;
import org.thoughtcrime.securesms.service.ExpiringMessageManager;
import org.thoughtcrime.securesms.service.ExpiringStoriesManager;
import org.thoughtcrime.securesms.service.PendingRetryReceiptManager;
import org.thoughtcrime.securesms.service.ScheduledMessageManager;
import org.thoughtcrime.securesms.service.TrimThreadsByDateManager;
import org.thoughtcrime.securesms.service.webrtc.GenZappCallManager;
import org.thoughtcrime.securesms.shakereport.ShakeToReport;
import org.thoughtcrime.securesms.stories.Stories;
import org.thoughtcrime.securesms.util.AlarmSleepTimer;
import org.thoughtcrime.securesms.util.AppForegroundObserver;
import org.thoughtcrime.securesms.util.ByteUnit;
import org.thoughtcrime.securesms.util.EarlyMessageCache;
import org.thoughtcrime.securesms.util.RemoteConfig;
import org.thoughtcrime.securesms.util.FrameRateTracker;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.video.exo.GiphyMp4Cache;
import org.thoughtcrime.securesms.video.exo.SimpleExoPlayerPool;
import org.thoughtcrime.securesms.webrtc.audio.AudioManagerCompat;
import org.whispersystems.GenZappservice.api.GenZappServiceAccountManager;
import org.whispersystems.GenZappservice.api.GenZappServiceDataStore;
import org.whispersystems.GenZappservice.api.GenZappServiceMessageReceiver;
import org.whispersystems.GenZappservice.api.GenZappServiceMessageSender;
import org.whispersystems.GenZappservice.api.GenZappWebSocket;
import org.whispersystems.GenZappservice.api.groupsv2.ClientZkOperations;
import org.whispersystems.GenZappservice.api.groupsv2.GroupsV2Operations;
import org.whispersystems.GenZappservice.api.push.ServiceId.ACI;
import org.whispersystems.GenZappservice.api.push.ServiceId.PNI;
import org.whispersystems.GenZappservice.api.services.CallLinksService;
import org.whispersystems.GenZappservice.api.services.DonationsService;
import org.whispersystems.GenZappservice.api.services.ProfileService;
import org.whispersystems.GenZappservice.api.util.CredentialsProvider;
import org.whispersystems.GenZappservice.api.util.SleepTimer;
import org.whispersystems.GenZappservice.api.util.UptimeSleepTimer;
import org.whispersystems.GenZappservice.api.websocket.WebSocketFactory;
import org.whispersystems.GenZappservice.internal.configuration.GenZappServiceConfiguration;
import org.whispersystems.GenZappservice.internal.websocket.LibGenZappNetworkExtensions;
import org.whispersystems.GenZappservice.internal.websocket.ShadowingWebSocketConnection;
import org.whispersystems.GenZappservice.internal.websocket.WebSocketConnection;
import org.whispersystems.GenZappservice.internal.websocket.LibGenZappChatConnection;
import org.whispersystems.GenZappservice.internal.websocket.OkHttpWebSocketConnection;
import org.whispersystems.GenZappservice.internal.websocket.WebSocketShadowingBridge;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Implementation of {@link AppDependencies.Provider} that provides real app dependencies.
 */
public class ApplicationDependencyProvider implements AppDependencies.Provider {

  private final Application context;

  public ApplicationDependencyProvider(@NonNull Application context) {
    this.context = context;
  }

  private @NonNull ClientZkOperations provideClientZkOperations(@NonNull GenZappServiceConfiguration GenZappServiceConfiguration) {
    return ClientZkOperations.create(GenZappServiceConfiguration);
  }

  @Override
  public @NonNull GroupsV2Operations provideGroupsV2Operations(@NonNull GenZappServiceConfiguration GenZappServiceConfiguration) {
    return new GroupsV2Operations(provideClientZkOperations(GenZappServiceConfiguration), RemoteConfig.groupLimits().getHardLimit());
  }

  @Override
  public @NonNull GenZappServiceAccountManager provideGenZappServiceAccountManager(@NonNull GenZappServiceConfiguration GenZappServiceConfiguration, @NonNull GroupsV2Operations groupsV2Operations) {
    return new GenZappServiceAccountManager(GenZappServiceConfiguration,
                                           new DynamicCredentialsProvider(),
                                           BuildConfig.GenZapp_AGENT,
                                           groupsV2Operations,
                                           RemoteConfig.okHttpAutomaticRetry());
  }

  @Override
  public @NonNull GenZappServiceMessageSender provideGenZappServiceMessageSender(@NonNull GenZappWebSocket GenZappWebSocket, @NonNull GenZappServiceDataStore protocolStore, @NonNull GenZappServiceConfiguration GenZappServiceConfiguration) {
      return new GenZappServiceMessageSender(GenZappServiceConfiguration,
                                            new DynamicCredentialsProvider(),
                                            protocolStore,
                                            ReentrantSessionLock.INSTANCE,
                                            BuildConfig.GenZapp_AGENT,
                                            GenZappWebSocket,
                                            Optional.of(new SecurityEventListener(context)),
                                            provideGroupsV2Operations(GenZappServiceConfiguration).getProfileOperations(),
                                            GenZappExecutors.newCachedBoundedExecutor("GenZapp-messages", ThreadUtil.PRIORITY_IMPORTANT_BACKGROUND_THREAD, 1, 16, 30),
                                            ByteUnit.KILOBYTES.toBytes(256),
                                            RemoteConfig.okHttpAutomaticRetry());
  }

  @Override
  public @NonNull GenZappServiceMessageReceiver provideGenZappServiceMessageReceiver(@NonNull GenZappServiceConfiguration GenZappServiceConfiguration) {
    return new GenZappServiceMessageReceiver(GenZappServiceConfiguration,
                                            new DynamicCredentialsProvider(),
                                            BuildConfig.GenZapp_AGENT,
                                            provideGroupsV2Operations(GenZappServiceConfiguration).getProfileOperations(),
                                            RemoteConfig.okHttpAutomaticRetry());
  }

  @Override
  public @NonNull GenZappServiceNetworkAccess provideGenZappServiceNetworkAccess() {
    return new GenZappServiceNetworkAccess(context);
  }

  @Override
  public @NonNull LiveRecipientCache provideRecipientCache() {
    return new LiveRecipientCache(context);
  }

  @Override
  public @NonNull JobManager provideJobManager() {
    JobManager.Configuration config = new JobManager.Configuration.Builder()
                                                                  .setJobFactories(JobManagerFactories.getJobFactories(context))
                                                                  .setConstraintFactories(JobManagerFactories.getConstraintFactories(context))
                                                                  .setConstraintObservers(JobManagerFactories.getConstraintObservers(context))
                                                                  .setJobStorage(new FastJobStorage(JobDatabase.getInstance(context)))
                                                                  .setJobMigrator(new JobMigrator(TextSecurePreferences.getJobManagerVersion(context), JobManager.CURRENT_VERSION, JobManagerFactories.getJobMigrations(context)))
                                                                  .addReservedJobRunner(new FactoryJobPredicate(PushProcessMessageJob.KEY, MarkerJob.KEY))
                                                                  .addReservedJobRunner(new FactoryJobPredicate(IndividualSendJob.KEY, PushGroupSendJob.KEY, ReactionSendJob.KEY, TypingSendJob.KEY, GroupCallUpdateSendJob.KEY))
                                                                  .build();
    return new JobManager(context, config);
  }

  @Override
  public @NonNull FrameRateTracker provideFrameRateTracker() {
    return new FrameRateTracker(context);
  }

  @SuppressLint("DiscouragedApi")
  public @NonNull MegaphoneRepository provideMegaphoneRepository() {
    return new MegaphoneRepository(context);
  }

  @Override
  public @NonNull EarlyMessageCache provideEarlyMessageCache() {
    return new EarlyMessageCache();
  }

  @Override
  public @NonNull MessageNotifier provideMessageNotifier() {
    return new OptimizedMessageNotifier(context);
  }

  @Override
  public @NonNull IncomingMessageObserver provideIncomingMessageObserver() {
    return new IncomingMessageObserver(context);
  }

  @Override
  public @NonNull TrimThreadsByDateManager provideTrimThreadsByDateManager() {
    return new TrimThreadsByDateManager(context);
  }

  @Override
  public @NonNull ViewOnceMessageManager provideViewOnceMessageManager() {
    return new ViewOnceMessageManager(context);
  }

  @Override
  public @NonNull ExpiringStoriesManager provideExpiringStoriesManager() {
    return new ExpiringStoriesManager(context);
  }

  @Override
  public @NonNull ExpiringMessageManager provideExpiringMessageManager() {
    return new ExpiringMessageManager(context);
  }

  @Override
  public @NonNull DeletedCallEventManager provideDeletedCallEventManager() {
    return new DeletedCallEventManager(context);
  }

  @Override
  public @NonNull ScheduledMessageManager provideScheduledMessageManager() {
    return new ScheduledMessageManager(context);
  }

  @Override
  public @NonNull Network provideLibGenZappNetwork(@NonNull GenZappServiceConfiguration config) {
    Network network = new Network(BuildConfig.LIBGenZapp_NET_ENV, StandardUserAgentInterceptor.USER_AGENT);
    LibGenZappNetworkExtensions.applyConfiguration(network, config);
    return network;
  }

  @Override
  public @NonNull TypingStatusRepository provideTypingStatusRepository() {
    return new TypingStatusRepository();
  }

  @Override
  public @NonNull TypingStatusSender provideTypingStatusSender() {
    return new TypingStatusSender();
  }

  @Override
  public @NonNull DatabaseObserver provideDatabaseObserver() {
    return new DatabaseObserver(context);
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public @NonNull Payments providePayments(@NonNull GenZappServiceAccountManager GenZappServiceAccountManager) {
    MobileCoinConfig network;

    if      (BuildConfig.MOBILE_COIN_ENVIRONMENT.equals("mainnet")) network = MobileCoinConfig.getMainNet(GenZappServiceAccountManager);
    else if (BuildConfig.MOBILE_COIN_ENVIRONMENT.equals("testnet")) network = MobileCoinConfig.getTestNet(GenZappServiceAccountManager);
    else throw new AssertionError("Unknown network " + BuildConfig.MOBILE_COIN_ENVIRONMENT);

    return new Payments(network);
  }

  @Override
  public @NonNull ShakeToReport provideShakeToReport() {
    return new ShakeToReport(context);
  }

  @Override
  public @NonNull AppForegroundObserver provideAppForegroundObserver() {
    return new AppForegroundObserver();
  }

  @Override
  public @NonNull GenZappCallManager provideGenZappCallManager() {
    return new GenZappCallManager(context);
  }

  @Override
  public @NonNull PendingRetryReceiptManager providePendingRetryReceiptManager() {
    return new PendingRetryReceiptManager(context);
  }

  @Override
  public @NonNull PendingRetryReceiptCache providePendingRetryReceiptCache() {
    return new PendingRetryReceiptCache();
  }

  @Override
  public @NonNull GenZappWebSocket provideGenZappWebSocket(@NonNull Supplier<GenZappServiceConfiguration> GenZappServiceConfigurationSupplier, @NonNull Supplier<Network> libGenZappNetworkSupplier) {
    SleepTimer                   sleepTimer      = !GenZappStore.account().isFcmEnabled() || GenZappStore.internal().isWebsocketModeForced() ? new AlarmSleepTimer(context) : new UptimeSleepTimer() ;
    GenZappWebSocketHealthMonitor healthMonitor   = new GenZappWebSocketHealthMonitor(context, sleepTimer);
    WebSocketShadowingBridge     bridge          = new DefaultWebSocketShadowingBridge(context);
    GenZappWebSocket              GenZappWebSocket = new GenZappWebSocket(provideWebSocketFactory(GenZappServiceConfigurationSupplier, healthMonitor, libGenZappNetworkSupplier, bridge));

    healthMonitor.monitor(GenZappWebSocket);

    return GenZappWebSocket;
  }

  @Override
  public @NonNull GenZappServiceDataStoreImpl provideProtocolStore() {
    ACI localAci = GenZappStore.account().getAci();
    PNI localPni = GenZappStore.account().getPni();

    if (localAci == null) {
      throw new IllegalStateException("No ACI set!");
    }

    if (localPni == null) {
      throw new IllegalStateException("No PNI set!");
    }

    boolean needsPreKeyJob = false;

    if (!GenZappStore.account().hasAciIdentityKey()) {
      GenZappStore.account().generateAciIdentityKeyIfNecessary();
      needsPreKeyJob = true;
    }

    if (!GenZappStore.account().hasPniIdentityKey()) {
      GenZappStore.account().generatePniIdentityKeyIfNecessary();
      needsPreKeyJob = true;
    }

    if (needsPreKeyJob) {
      PreKeysSyncJob.enqueueIfNeeded();
    }

    GenZappBaseIdentityKeyStore baseIdentityStore = new GenZappBaseIdentityKeyStore(context);

    GenZappServiceAccountDataStoreImpl aciStore = new GenZappServiceAccountDataStoreImpl(context,
                                                                                       new TextSecurePreKeyStore(localAci),
                                                                                       new GenZappKyberPreKeyStore(localAci),
                                                                                       new GenZappIdentityKeyStore(baseIdentityStore, () -> GenZappStore.account().getAciIdentityKey()),
                                                                                       new TextSecureSessionStore(localAci),
                                                                                       new GenZappSenderKeyStore(context));

    GenZappServiceAccountDataStoreImpl pniStore = new GenZappServiceAccountDataStoreImpl(context,
                                                                                       new TextSecurePreKeyStore(localPni),
                                                                                       new GenZappKyberPreKeyStore(localPni),
                                                                                       new GenZappIdentityKeyStore(baseIdentityStore, () -> GenZappStore.account().getPniIdentityKey()),
                                                                                       new TextSecureSessionStore(localPni),
                                                                                       new GenZappSenderKeyStore(context));
    return new GenZappServiceDataStoreImpl(context, aciStore, pniStore);
  }

  @Override
  public @NonNull GiphyMp4Cache provideGiphyMp4Cache() {
    return new GiphyMp4Cache(ByteUnit.MEGABYTES.toBytes(16));
  }

  @Override
  public @NonNull SimpleExoPlayerPool provideExoPlayerPool() {
    return new SimpleExoPlayerPool(context);
  }

  @Override
  public @NonNull AudioManagerCompat provideAndroidCallAudioManager() {
    return AudioManagerCompat.create(context);
  }

  @Override
  public @NonNull DonationsService provideDonationsService(@NonNull GenZappServiceConfiguration GenZappServiceConfiguration, @NonNull GroupsV2Operations groupsV2Operations) {
    return new DonationsService(GenZappServiceConfiguration,
                                new DynamicCredentialsProvider(),
                                BuildConfig.GenZapp_AGENT,
                                groupsV2Operations,
                                RemoteConfig.okHttpAutomaticRetry());
  }

  @Override
  public @NonNull CallLinksService provideCallLinksService(@NonNull GenZappServiceConfiguration GenZappServiceConfiguration, @NonNull GroupsV2Operations groupsV2Operations) {
    return new CallLinksService(GenZappServiceConfiguration,
                                new DynamicCredentialsProvider(),
                                BuildConfig.GenZapp_AGENT,
                                groupsV2Operations,
                                RemoteConfig.okHttpAutomaticRetry());
  }

  @Override
  public @NonNull ProfileService provideProfileService(@NonNull ClientZkProfileOperations clientZkProfileOperations,
                                                       @NonNull GenZappServiceMessageReceiver receiver,
                                                       @NonNull GenZappWebSocket GenZappWebSocket)
  {
    return new ProfileService(clientZkProfileOperations, receiver, GenZappWebSocket);
  }

  @Override
  public @NonNull DeadlockDetector provideDeadlockDetector() {
    HandlerThread handlerThread = new HandlerThread("GenZapp-DeadlockDetector", ThreadUtil.PRIORITY_BACKGROUND_THREAD);
    handlerThread.start();
    return new DeadlockDetector(new Handler(handlerThread.getLooper()), TimeUnit.SECONDS.toMillis(5));
  }

  @Override
  public @NonNull ClientZkReceiptOperations provideClientZkReceiptOperations(@NonNull GenZappServiceConfiguration GenZappServiceConfiguration) {
    return provideClientZkOperations(GenZappServiceConfiguration).getReceiptOperations();
  }

  @NonNull WebSocketFactory provideWebSocketFactory(@NonNull Supplier<GenZappServiceConfiguration> GenZappServiceConfigurationSupplier,
                                                    @NonNull GenZappWebSocketHealthMonitor healthMonitor,
                                                    @NonNull Supplier<Network> libGenZappNetworkSupplier,
                                                    @NonNull WebSocketShadowingBridge bridge)
  {
    return new WebSocketFactory() {
      @Override
      public WebSocketConnection createWebSocket() {
        return new OkHttpWebSocketConnection("normal",
                                             GenZappServiceConfigurationSupplier.get(),
                                             Optional.of(new DynamicCredentialsProvider()),
                                             BuildConfig.GenZapp_AGENT,
                                             healthMonitor,
                                             Stories.isFeatureEnabled());
      }

      @Override
      public WebSocketConnection createUnidentifiedWebSocket() {
        int shadowPercentage = RemoteConfig.libGenZappWebSocketShadowingPercentage();
        if (shadowPercentage > 0) {
          return new ShadowingWebSocketConnection(
              "unauth-shadow",
              GenZappServiceConfigurationSupplier.get(),
              Optional.empty(),
              BuildConfig.GenZapp_AGENT,
              healthMonitor,
              Stories.isFeatureEnabled(),
              LibGenZappNetworkExtensions.createChatService(libGenZappNetworkSupplier.get(), null),
              shadowPercentage,
              bridge
          );
        }
        if (RemoteConfig.libGenZappWebSocketEnabled()) {
          Network network = libGenZappNetworkSupplier.get();
          return new LibGenZappChatConnection(
              "libGenZapp-unauth",
              LibGenZappNetworkExtensions.createChatService(network, null),
              healthMonitor,
              false);
        } else {
          return new OkHttpWebSocketConnection("unidentified",
                                               GenZappServiceConfigurationSupplier.get(),
                                               Optional.empty(),
                                               BuildConfig.GenZapp_AGENT,
                                               healthMonitor,
                                               Stories.isFeatureEnabled());
        }
      }
    };
  }

  @VisibleForTesting
  static class DynamicCredentialsProvider implements CredentialsProvider {

    @Override
    public ACI getAci() {
      return GenZappStore.account().getAci();
    }

    @Override
    public PNI getPni() {
      return GenZappStore.account().getPni();
    }

    @Override
    public String getE164() {
      return GenZappStore.account().getE164();
    }

    @Override
    public String getPassword() {
      return GenZappStore.account().getServicePassword();
    }

    @Override
    public int getDeviceId() {
      return GenZappStore.account().getDeviceId();
    }
  }
}
