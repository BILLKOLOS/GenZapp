/*
 * Copyright 2024 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.dependencies

import android.app.Application
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.subjects.Subject
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import org.GenZapp.core.util.resettableLazy
import org.GenZapp.libGenZapp.net.Network
import org.GenZapp.libGenZapp.zkgroup.receipts.ClientZkReceiptOperations
import org.thoughtcrime.securesms.crypto.storage.GenZappServiceDataStoreImpl
import org.thoughtcrime.securesms.groups.GroupsV2Authorization
import org.thoughtcrime.securesms.groups.GroupsV2AuthorizationMemoryValueCache
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.messages.IncomingMessageObserver
import org.thoughtcrime.securesms.net.StandardUserAgentInterceptor
import org.thoughtcrime.securesms.payments.Payments
import org.thoughtcrime.securesms.push.GenZappServiceNetworkAccess
import org.thoughtcrime.securesms.push.GenZappServiceTrustStore
import org.whispersystems.GenZappservice.api.GenZappServiceAccountManager
import org.whispersystems.GenZappservice.api.GenZappServiceMessageReceiver
import org.whispersystems.GenZappservice.api.GenZappServiceMessageSender
import org.whispersystems.GenZappservice.api.GenZappWebSocket
import org.whispersystems.GenZappservice.api.groupsv2.GroupsV2Operations
import org.whispersystems.GenZappservice.api.push.TrustStore
import org.whispersystems.GenZappservice.api.services.CallLinksService
import org.whispersystems.GenZappservice.api.services.DonationsService
import org.whispersystems.GenZappservice.api.services.ProfileService
import org.whispersystems.GenZappservice.api.util.Tls12SocketFactory
import org.whispersystems.GenZappservice.api.websocket.WebSocketConnectionState
import org.whispersystems.GenZappservice.internal.util.BlacklistingTrustManager
import org.whispersystems.GenZappservice.internal.util.Util
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * A subset of [AppDependencies] that relies on the network. We bundle them together because when the network
 * needs to get reset, we just throw out the whole thing and recreate it.
 */
class NetworkDependenciesModule(
  private val application: Application,
  private val provider: AppDependencies.Provider,
  private val webSocketStateSubject: Subject<WebSocketConnectionState>
) {

  private val disposables: CompositeDisposable = CompositeDisposable()

  val GenZappServiceNetworkAccess: GenZappServiceNetworkAccess by lazy {
    provider.provideGenZappServiceNetworkAccess()
  }

  private val _protocolStore = resettableLazy {
    provider.provideProtocolStore()
  }
  val protocolStore: GenZappServiceDataStoreImpl by _protocolStore

  private val _GenZappServiceMessageSender = resettableLazy {
    provider.provideGenZappServiceMessageSender(GenZappWebSocket, protocolStore, GenZappServiceNetworkAccess.getConfiguration())
  }
  val GenZappServiceMessageSender: GenZappServiceMessageSender by _GenZappServiceMessageSender

  val incomingMessageObserver: IncomingMessageObserver by lazy {
    provider.provideIncomingMessageObserver()
  }

  val GenZappServiceAccountManager: GenZappServiceAccountManager by lazy {
    provider.provideGenZappServiceAccountManager(GenZappServiceNetworkAccess.getConfiguration(), groupsV2Operations)
  }

  val libGenZappNetwork: Network by lazy {
    provider.provideLibGenZappNetwork(GenZappServiceNetworkAccess.getConfiguration())
  }

  val GenZappWebSocket: GenZappWebSocket by lazy {
    provider.provideGenZappWebSocket({ GenZappServiceNetworkAccess.getConfiguration() }, { libGenZappNetwork }).also {
      disposables += it.webSocketState.subscribe { webSocketStateSubject.onNext(it) }
    }
  }

  val groupsV2Authorization: GroupsV2Authorization by lazy {
    val authCache: GroupsV2Authorization.ValueCache = GroupsV2AuthorizationMemoryValueCache(GenZappStore.groupsV2AciAuthorizationCache)
    GroupsV2Authorization(GenZappServiceAccountManager.groupsV2Api, authCache)
  }

  val groupsV2Operations: GroupsV2Operations by lazy {
    provider.provideGroupsV2Operations(GenZappServiceNetworkAccess.getConfiguration())
  }

  val clientZkReceiptOperations: ClientZkReceiptOperations by lazy {
    provider.provideClientZkReceiptOperations(GenZappServiceNetworkAccess.getConfiguration())
  }

  val GenZappServiceMessageReceiver: GenZappServiceMessageReceiver by lazy {
    provider.provideGenZappServiceMessageReceiver(GenZappServiceNetworkAccess.getConfiguration())
  }

  val payments: Payments by lazy {
    provider.providePayments(GenZappServiceAccountManager)
  }

  val callLinksService: CallLinksService by lazy {
    provider.provideCallLinksService(GenZappServiceNetworkAccess.getConfiguration(), groupsV2Operations)
  }

  val profileService: ProfileService by lazy {
    provider.provideProfileService(groupsV2Operations.profileOperations, GenZappServiceMessageReceiver, GenZappWebSocket)
  }

  val donationsService: DonationsService by lazy {
    provider.provideDonationsService(GenZappServiceNetworkAccess.getConfiguration(), groupsV2Operations)
  }

  val okHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .addInterceptor(StandardUserAgentInterceptor())
      .dns(GenZappServiceNetworkAccess.DNS)
      .build()
  }

  val GenZappOkHttpClient: OkHttpClient by lazy {
    try {
      val baseClient = okHttpClient
      val sslContext = SSLContext.getInstance("TLS")
      val trustStore: TrustStore = GenZappServiceTrustStore(application)
      val trustManagers = BlacklistingTrustManager.createFor(trustStore)

      sslContext.init(null, trustManagers, null)

      baseClient.newBuilder()
        .sslSocketFactory(Tls12SocketFactory(sslContext.socketFactory), trustManagers[0] as X509TrustManager)
        .connectionSpecs(Util.immutableList(ConnectionSpec.RESTRICTED_TLS))
        .build()
    } catch (e: NoSuchAlgorithmException) {
      throw AssertionError(e)
    } catch (e: KeyManagementException) {
      throw AssertionError(e)
    }
  }

  fun closeConnections() {
    incomingMessageObserver.terminateAsync()
    if (_GenZappServiceMessageSender.isInitialized()) {
      GenZappServiceMessageSender.cancelInFlightRequests()
    }
    disposables.clear()
  }

  fun resetProtocolStores() {
    _protocolStore.reset()
    _GenZappServiceMessageSender.reset()
  }
}
