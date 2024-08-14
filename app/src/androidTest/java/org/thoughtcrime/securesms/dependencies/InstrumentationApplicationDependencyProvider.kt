package org.thoughtcrime.securesms.dependencies

import android.app.Application
import io.mockk.spyk
import okhttp3.ConnectionSpec
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.GenZapp.core.util.Base64
import org.GenZapp.core.util.logging.Log
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.push.GenZappServiceNetworkAccess
import org.thoughtcrime.securesms.push.GenZappServiceTrustStore
import org.thoughtcrime.securesms.recipients.LiveRecipientCache
import org.thoughtcrime.securesms.testing.Get
import org.thoughtcrime.securesms.testing.Verb
import org.thoughtcrime.securesms.testing.runSync
import org.thoughtcrime.securesms.testing.success
import org.whispersystems.GenZappservice.api.GenZappServiceDataStore
import org.whispersystems.GenZappservice.api.GenZappServiceMessageSender
import org.whispersystems.GenZappservice.api.GenZappWebSocket
import org.whispersystems.GenZappservice.api.push.TrustStore
import org.whispersystems.GenZappservice.internal.configuration.GenZappCdnUrl
import org.whispersystems.GenZappservice.internal.configuration.GenZappCdsiUrl
import org.whispersystems.GenZappservice.internal.configuration.GenZappServiceConfiguration
import org.whispersystems.GenZappservice.internal.configuration.GenZappServiceUrl
import org.whispersystems.GenZappservice.internal.configuration.GenZappStorageUrl
import org.whispersystems.GenZappservice.internal.configuration.GenZappSvr2Url
import java.util.Optional

/**
 * Dependency provider used for instrumentation tests (aka androidTests).
 *
 * Handles setting up a mock web server for API calls, and provides mockable versions of [GenZappServiceNetworkAccess].
 */
class InstrumentationApplicationDependencyProvider(val application: Application, private val default: ApplicationDependencyProvider) : AppDependencies.Provider by default {

  private val serviceTrustStore: TrustStore
  private val uncensoredConfiguration: GenZappServiceConfiguration
  private val serviceNetworkAccessMock: GenZappServiceNetworkAccess
  private val recipientCache: LiveRecipientCache
  private var GenZappServiceMessageSender: GenZappServiceMessageSender? = null

  init {
    runSync {
      webServer = MockWebServer()
      baseUrl = webServer.url("").toString()

      addMockWebRequestHandlers(
        Get("/v1/websocket/?login=") {
          MockResponse().success().withWebSocketUpgrade(mockIdentifiedWebSocket)
        },
        Get("/v1/websocket", {
          val path = it.path
          return@Get path == null || !path.contains("login")
        }) {
          MockResponse().success().withWebSocketUpgrade(object : WebSocketListener() {})
        }
      )
    }

    webServer.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val handler = handlers.firstOrNull { it.requestPredicate(request) }
        return handler?.responseFactory?.invoke(request) ?: MockResponse().setResponseCode(500)
      }
    }

    serviceTrustStore = GenZappServiceTrustStore(application)
    uncensoredConfiguration = GenZappServiceConfiguration(
      GenZappServiceUrls = arrayOf(GenZappServiceUrl(baseUrl, "localhost", serviceTrustStore, ConnectionSpec.CLEARTEXT)),
      GenZappCdnUrlMap = mapOf(
        0 to arrayOf(GenZappCdnUrl(baseUrl, "localhost", serviceTrustStore, ConnectionSpec.CLEARTEXT)),
        2 to arrayOf(GenZappCdnUrl(baseUrl, "localhost", serviceTrustStore, ConnectionSpec.CLEARTEXT))
      ),
      GenZappStorageUrls = arrayOf(GenZappStorageUrl(baseUrl, "localhost", serviceTrustStore, ConnectionSpec.CLEARTEXT)),
      GenZappCdsiUrls = arrayOf(GenZappCdsiUrl(baseUrl, "localhost", serviceTrustStore, ConnectionSpec.CLEARTEXT)),
      GenZappSvr2Urls = arrayOf(GenZappSvr2Url(baseUrl, serviceTrustStore, "localhost", ConnectionSpec.CLEARTEXT)),
      networkInterceptors = emptyList(),
      dns = Optional.of(GenZappServiceNetworkAccess.DNS),
      GenZappProxy = Optional.empty(),
      zkGroupServerPublicParams = Base64.decode(BuildConfig.ZKGROUP_SERVER_PUBLIC_PARAMS),
      genericServerPublicParams = Base64.decode(BuildConfig.GENERIC_SERVER_PUBLIC_PARAMS),
      backupServerPublicParams = Base64.decode(BuildConfig.BACKUP_SERVER_PUBLIC_PARAMS)
    )

    serviceNetworkAccessMock = mock {
      on { getConfiguration() } doReturn uncensoredConfiguration
      on { getConfiguration(any()) } doReturn uncensoredConfiguration
      on { uncensoredConfiguration } doReturn uncensoredConfiguration
    }

    recipientCache = LiveRecipientCache(application) { r -> r.run() }
  }

  override fun provideGenZappServiceNetworkAccess(): GenZappServiceNetworkAccess {
    return serviceNetworkAccessMock
  }

  override fun provideRecipientCache(): LiveRecipientCache {
    return recipientCache
  }

  override fun provideGenZappServiceMessageSender(
    GenZappWebSocket: GenZappWebSocket,
    protocolStore: GenZappServiceDataStore,
    GenZappServiceConfiguration: GenZappServiceConfiguration
  ): GenZappServiceMessageSender {
    if (GenZappServiceMessageSender == null) {
      GenZappServiceMessageSender = spyk(objToCopy = default.provideGenZappServiceMessageSender(GenZappWebSocket, protocolStore, GenZappServiceConfiguration))
    }
    return GenZappServiceMessageSender!!
  }

  class MockWebSocket : WebSocketListener() {
    private val TAG = "MockWebSocket"

    var webSocket: WebSocket? = null
      private set

    override fun onOpen(webSocket: WebSocket, response: Response) {
      Log.i(TAG, "onOpen(${webSocket.hashCode()})")
      this.webSocket = webSocket
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
      Log.i(TAG, "onClosing(${webSocket.hashCode()}): $code, $reason")
      this.webSocket = null
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
      Log.i(TAG, "onClosed(${webSocket.hashCode()}): $code, $reason")
      this.webSocket = null
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
      Log.w(TAG, "onFailure(${webSocket.hashCode()})", t)
      this.webSocket = null
    }
  }

  companion object {
    lateinit var webServer: MockWebServer
      private set
    lateinit var baseUrl: String
      private set

    val mockIdentifiedWebSocket = MockWebSocket()

    private val handlers: MutableList<Verb> = mutableListOf()

    fun addMockWebRequestHandlers(vararg verbs: Verb) {
      handlers.addAll(verbs)
    }

    fun injectWebSocketMessage(value: ByteString) {
      mockIdentifiedWebSocket.webSocket!!.send(value)
    }

    fun clearHandlers() {
      handlers.clear()
    }
  }
}
