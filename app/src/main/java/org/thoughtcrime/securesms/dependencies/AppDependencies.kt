package org.thoughtcrime.securesms.dependencies

import android.annotation.SuppressLint
import android.app.Application
import androidx.annotation.MainThread
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.Subject
import okhttp3.OkHttpClient
import org.GenZapp.core.util.concurrent.DeadlockDetector
import org.GenZapp.core.util.resettableLazy
import org.GenZapp.libGenZapp.net.Network
import org.GenZapp.libGenZapp.zkgroup.profiles.ClientZkProfileOperations
import org.GenZapp.libGenZapp.zkgroup.receipts.ClientZkReceiptOperations
import org.thoughtcrime.securesms.components.TypingStatusRepository
import org.thoughtcrime.securesms.components.TypingStatusSender
import org.thoughtcrime.securesms.crypto.storage.GenZappServiceDataStoreImpl
import org.thoughtcrime.securesms.database.DatabaseObserver
import org.thoughtcrime.securesms.database.PendingRetryReceiptCache
import org.thoughtcrime.securesms.groups.GroupsV2Authorization
import org.thoughtcrime.securesms.jobmanager.JobManager
import org.thoughtcrime.securesms.megaphone.MegaphoneRepository
import org.thoughtcrime.securesms.messages.IncomingMessageObserver
import org.thoughtcrime.securesms.notifications.MessageNotifier
import org.thoughtcrime.securesms.payments.Payments
import org.thoughtcrime.securesms.push.GenZappServiceNetworkAccess
import org.thoughtcrime.securesms.recipients.LiveRecipientCache
import org.thoughtcrime.securesms.revealable.ViewOnceMessageManager
import org.thoughtcrime.securesms.service.DeletedCallEventManager
import org.thoughtcrime.securesms.service.ExpiringMessageManager
import org.thoughtcrime.securesms.service.ExpiringStoriesManager
import org.thoughtcrime.securesms.service.PendingRetryReceiptManager
import org.thoughtcrime.securesms.service.ScheduledMessageManager
import org.thoughtcrime.securesms.service.TrimThreadsByDateManager
import org.thoughtcrime.securesms.service.webrtc.GenZappCallManager
import org.thoughtcrime.securesms.shakereport.ShakeToReport
import org.thoughtcrime.securesms.util.AppForegroundObserver
import org.thoughtcrime.securesms.util.EarlyMessageCache
import org.thoughtcrime.securesms.util.FrameRateTracker
import org.thoughtcrime.securesms.video.exo.GiphyMp4Cache
import org.thoughtcrime.securesms.video.exo.SimpleExoPlayerPool
import org.thoughtcrime.securesms.webrtc.audio.AudioManagerCompat
import org.whispersystems.GenZappservice.api.GenZappServiceAccountManager
import org.whispersystems.GenZappservice.api.GenZappServiceDataStore
import org.whispersystems.GenZappservice.api.GenZappServiceMessageReceiver
import org.whispersystems.GenZappservice.api.GenZappServiceMessageSender
import org.whispersystems.GenZappservice.api.GenZappWebSocket
import org.whispersystems.GenZappservice.api.groupsv2.GroupsV2Operations
import org.whispersystems.GenZappservice.api.services.CallLinksService
import org.whispersystems.GenZappservice.api.services.DonationsService
import org.whispersystems.GenZappservice.api.services.ProfileService
import org.whispersystems.GenZappservice.api.websocket.WebSocketConnectionState
import org.whispersystems.GenZappservice.internal.configuration.GenZappServiceConfiguration
import java.util.function.Supplier

/**
 * Location for storing and retrieving application-scoped singletons. Users must call
 * [.init] before using any of the methods, preferably early on in
 * [Application.onCreate].
 *
 * All future application-scoped singletons should be written as normal objects, then placed here
 * to manage their singleton-ness.
 */
@SuppressLint("StaticFieldLeak")
object AppDependencies {
  private lateinit var _application: Application
  private lateinit var provider: Provider

  // Needs special initialization because it needs to be created on the main thread
  private lateinit var _appForegroundObserver: AppForegroundObserver

  @JvmStatic
  @MainThread
  fun init(application: Application, provider: Provider) {
    if (this::_application.isInitialized || this::provider.isInitialized) {
      throw IllegalStateException("Already initialized!")
    }

    _application = application
    AppDependencies.provider = provider

    _appForegroundObserver = provider.provideAppForegroundObserver()
    _appForegroundObserver.begin()
  }

  @JvmStatic
  val isInitialized: Boolean
    get() = this::_application.isInitialized

  @JvmStatic
  val application: Application
    get() = _application

  @JvmStatic
  val appForegroundObserver: AppForegroundObserver
    get() = _appForegroundObserver

  @JvmStatic
  val recipientCache: LiveRecipientCache by lazy {
    provider.provideRecipientCache()
  }

  @JvmStatic
  val jobManager: JobManager by lazy {
    provider.provideJobManager()
  }

  @JvmStatic
  val frameRateTracker: FrameRateTracker by lazy {
    provider.provideFrameRateTracker()
  }

  @JvmStatic
  val megaphoneRepository: MegaphoneRepository by lazy {
    provider.provideMegaphoneRepository()
  }

  @JvmStatic
  val earlyMessageCache: EarlyMessageCache by lazy {
    provider.provideEarlyMessageCache()
  }

  @JvmStatic
  val typingStatusRepository: TypingStatusRepository by lazy {
    provider.provideTypingStatusRepository()
  }

  @JvmStatic
  val typingStatusSender: TypingStatusSender by lazy {
    provider.provideTypingStatusSender()
  }

  @JvmStatic
  val databaseObserver: DatabaseObserver by lazy {
    provider.provideDatabaseObserver()
  }

  @JvmStatic
  val trimThreadsByDateManager: TrimThreadsByDateManager by lazy {
    provider.provideTrimThreadsByDateManager()
  }

  @JvmStatic
  val viewOnceMessageManager: ViewOnceMessageManager by lazy {
    provider.provideViewOnceMessageManager()
  }

  @JvmStatic
  val expiringMessageManager: ExpiringMessageManager by lazy {
    provider.provideExpiringMessageManager()
  }

  @JvmStatic
  val deletedCallEventManager: DeletedCallEventManager by lazy {
    provider.provideDeletedCallEventManager()
  }

  @JvmStatic
  val GenZappCallManager: GenZappCallManager by lazy {
    provider.provideGenZappCallManager()
  }

  @JvmStatic
  val shakeToReport: ShakeToReport by lazy {
    provider.provideShakeToReport()
  }

  @JvmStatic
  val pendingRetryReceiptManager: PendingRetryReceiptManager by lazy {
    provider.providePendingRetryReceiptManager()
  }

  @JvmStatic
  val pendingRetryReceiptCache: PendingRetryReceiptCache by lazy {
    provider.providePendingRetryReceiptCache()
  }

  @JvmStatic
  val messageNotifier: MessageNotifier by lazy {
    provider.provideMessageNotifier()
  }

  @JvmStatic
  val giphyMp4Cache: GiphyMp4Cache by lazy {
    provider.provideGiphyMp4Cache()
  }

  @JvmStatic
  val exoPlayerPool: SimpleExoPlayerPool by lazy {
    provider.provideExoPlayerPool()
  }

  @JvmStatic
  val deadlockDetector: DeadlockDetector by lazy {
    provider.provideDeadlockDetector()
  }

  @JvmStatic
  val expireStoriesManager: ExpiringStoriesManager by lazy {
    provider.provideExpiringStoriesManager()
  }

  @JvmStatic
  val scheduledMessageManager: ScheduledMessageManager by lazy {
    provider.provideScheduledMessageManager()
  }

  @JvmStatic
  val androidCallAudioManager: AudioManagerCompat by lazy {
    provider.provideAndroidCallAudioManager()
  }

  private val _webSocketObserver: Subject<WebSocketConnectionState> = BehaviorSubject.create()

  /**
   * An observable that emits the current state of the WebSocket connection across the various lifecycles
   * of the [GenZappWebSocket].
   */
  @JvmStatic
  val webSocketObserver: Observable<WebSocketConnectionState> = _webSocketObserver

  private val _networkModule = resettableLazy {
    NetworkDependenciesModule(application, provider, _webSocketObserver)
  }
  private val networkModule by _networkModule

  @JvmStatic
  val GenZappServiceNetworkAccess: GenZappServiceNetworkAccess
    get() = networkModule.GenZappServiceNetworkAccess

  @JvmStatic
  val protocolStore: GenZappServiceDataStoreImpl
    get() = networkModule.protocolStore

  @JvmStatic
  val GenZappServiceMessageSender: GenZappServiceMessageSender
    get() = networkModule.GenZappServiceMessageSender

  @JvmStatic
  val GenZappServiceAccountManager: GenZappServiceAccountManager
    get() = networkModule.GenZappServiceAccountManager

  @JvmStatic
  val GenZappServiceMessageReceiver: GenZappServiceMessageReceiver
    get() = networkModule.GenZappServiceMessageReceiver

  @JvmStatic
  val incomingMessageObserver: IncomingMessageObserver
    get() = networkModule.incomingMessageObserver

  @JvmStatic
  val libGenZappNetwork: Network
    get() = networkModule.libGenZappNetwork

  @JvmStatic
  val GenZappWebSocket: GenZappWebSocket
    get() = networkModule.GenZappWebSocket

  @JvmStatic
  val groupsV2Authorization: GroupsV2Authorization
    get() = networkModule.groupsV2Authorization

  @JvmStatic
  val groupsV2Operations: GroupsV2Operations
    get() = networkModule.groupsV2Operations

  @JvmStatic
  val clientZkReceiptOperations
    get() = networkModule.clientZkReceiptOperations

  @JvmStatic
  val payments: Payments
    get() = networkModule.payments

  @JvmStatic
  val callLinksService: CallLinksService
    get() = networkModule.callLinksService

  @JvmStatic
  val profileService: ProfileService
    get() = networkModule.profileService

  @JvmStatic
  val donationsService: DonationsService
    get() = networkModule.donationsService

  @JvmStatic
  val okHttpClient: OkHttpClient
    get() = networkModule.okHttpClient

  @JvmStatic
  val GenZappOkHttpClient: OkHttpClient
    get() = networkModule.GenZappOkHttpClient

  @JvmStatic
  fun resetProtocolStores() {
    networkModule.resetProtocolStores()
  }

  @JvmStatic
  fun resetNetwork() {
    networkModule.closeConnections()
    _networkModule.reset()
  }

  interface Provider {
    fun provideGroupsV2Operations(GenZappServiceConfiguration: GenZappServiceConfiguration): GroupsV2Operations
    fun provideGenZappServiceAccountManager(GenZappServiceConfiguration: GenZappServiceConfiguration, groupsV2Operations: GroupsV2Operations): GenZappServiceAccountManager
    fun provideGenZappServiceMessageSender(GenZappWebSocket: GenZappWebSocket, protocolStore: GenZappServiceDataStore, GenZappServiceConfiguration: GenZappServiceConfiguration): GenZappServiceMessageSender
    fun provideGenZappServiceMessageReceiver(GenZappServiceConfiguration: GenZappServiceConfiguration): GenZappServiceMessageReceiver
    fun provideGenZappServiceNetworkAccess(): GenZappServiceNetworkAccess
    fun provideRecipientCache(): LiveRecipientCache
    fun provideJobManager(): JobManager
    fun provideFrameRateTracker(): FrameRateTracker
    fun provideMegaphoneRepository(): MegaphoneRepository
    fun provideEarlyMessageCache(): EarlyMessageCache
    fun provideMessageNotifier(): MessageNotifier
    fun provideIncomingMessageObserver(): IncomingMessageObserver
    fun provideTrimThreadsByDateManager(): TrimThreadsByDateManager
    fun provideViewOnceMessageManager(): ViewOnceMessageManager
    fun provideExpiringStoriesManager(): ExpiringStoriesManager
    fun provideExpiringMessageManager(): ExpiringMessageManager
    fun provideDeletedCallEventManager(): DeletedCallEventManager
    fun provideTypingStatusRepository(): TypingStatusRepository
    fun provideTypingStatusSender(): TypingStatusSender
    fun provideDatabaseObserver(): DatabaseObserver
    fun providePayments(GenZappServiceAccountManager: GenZappServiceAccountManager): Payments
    fun provideShakeToReport(): ShakeToReport
    fun provideAppForegroundObserver(): AppForegroundObserver
    fun provideGenZappCallManager(): GenZappCallManager
    fun providePendingRetryReceiptManager(): PendingRetryReceiptManager
    fun providePendingRetryReceiptCache(): PendingRetryReceiptCache
    fun provideGenZappWebSocket(GenZappServiceConfigurationSupplier: Supplier<GenZappServiceConfiguration>, libGenZappNetworkSupplier: Supplier<Network>): GenZappWebSocket
    fun provideProtocolStore(): GenZappServiceDataStoreImpl
    fun provideGiphyMp4Cache(): GiphyMp4Cache
    fun provideExoPlayerPool(): SimpleExoPlayerPool
    fun provideAndroidCallAudioManager(): AudioManagerCompat
    fun provideDonationsService(GenZappServiceConfiguration: GenZappServiceConfiguration, groupsV2Operations: GroupsV2Operations): DonationsService
    fun provideCallLinksService(GenZappServiceConfiguration: GenZappServiceConfiguration, groupsV2Operations: GroupsV2Operations): CallLinksService
    fun provideProfileService(profileOperations: ClientZkProfileOperations, GenZappServiceMessageReceiver: GenZappServiceMessageReceiver, GenZappWebSocket: GenZappWebSocket): ProfileService
    fun provideDeadlockDetector(): DeadlockDetector
    fun provideClientZkReceiptOperations(GenZappServiceConfiguration: GenZappServiceConfiguration): ClientZkReceiptOperations
    fun provideScheduledMessageManager(): ScheduledMessageManager
    fun provideLibGenZappNetwork(config: GenZappServiceConfiguration): Network
  }
}
