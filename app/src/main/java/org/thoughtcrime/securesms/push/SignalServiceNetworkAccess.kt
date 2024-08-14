package org.thoughtcrime.securesms.push

import android.content.Context
import com.google.i18n.phonenumbers.PhoneNumberUtil
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.TlsVersion
import org.GenZapp.core.util.Base64
import org.GenZapp.core.util.logging.Log
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.net.CustomDns
import org.thoughtcrime.securesms.net.DeprecatedClientPreventionInterceptor
import org.thoughtcrime.securesms.net.DeviceTransferBlockingInterceptor
import org.thoughtcrime.securesms.net.RemoteDeprecationDetectorInterceptor
import org.thoughtcrime.securesms.net.SequentialDns
import org.thoughtcrime.securesms.net.StandardUserAgentInterceptor
import org.thoughtcrime.securesms.net.StaticDns
import org.whispersystems.GenZappservice.api.push.TrustStore
import org.whispersystems.GenZappservice.internal.configuration.GenZappCdnUrl
import org.whispersystems.GenZappservice.internal.configuration.GenZappCdsiUrl
import org.whispersystems.GenZappservice.internal.configuration.GenZappKeyBackupServiceUrl
import org.whispersystems.GenZappservice.internal.configuration.GenZappServiceConfiguration
import org.whispersystems.GenZappservice.internal.configuration.GenZappServiceUrl
import org.whispersystems.GenZappservice.internal.configuration.GenZappStorageUrl
import org.whispersystems.GenZappservice.internal.configuration.GenZappSvr2Url
import java.io.IOException
import java.util.Optional

/**
 * Provides a [GenZappServiceConfiguration] to be used with our service layer.
 * If you're looking for a place to start, look at [getConfiguration].
 */
open class GenZappServiceNetworkAccess(context: Context) {
  companion object {
    private val TAG = Log.tag(GenZappServiceNetworkAccess::class.java)

    @JvmField
    val DNS: Dns = SequentialDns(
      Dns.SYSTEM,
      CustomDns("1.1.1.1"),
      StaticDns(
        mapOf(
          BuildConfig.GenZapp_URL.stripProtocol() to BuildConfig.GenZapp_SERVICE_IPS.toSet(),
          BuildConfig.STORAGE_URL.stripProtocol() to BuildConfig.GenZapp_STORAGE_IPS.toSet(),
          BuildConfig.GenZapp_CDN_URL.stripProtocol() to BuildConfig.GenZapp_CDN_IPS.toSet(),
          BuildConfig.GenZapp_CDN2_URL.stripProtocol() to BuildConfig.GenZapp_CDN2_IPS.toSet(),
          BuildConfig.GenZapp_CDN3_URL.stripProtocol() to BuildConfig.GenZapp_CDN3_IPS.toSet(),
          BuildConfig.GenZapp_SFU_URL.stripProtocol() to BuildConfig.GenZapp_SFU_IPS.toSet(),
          BuildConfig.CONTENT_PROXY_HOST.stripProtocol() to BuildConfig.GenZapp_CONTENT_PROXY_IPS.toSet(),
          BuildConfig.GenZapp_CDSI_URL.stripProtocol() to BuildConfig.GenZapp_CDSI_IPS.toSet(),
          BuildConfig.GenZapp_SVR2_URL.stripProtocol() to BuildConfig.GenZapp_SVR2_IPS.toSet()
        )
      )
    )

    private fun String.stripProtocol(): String {
      return this.removePrefix("https://")
    }

    private const val COUNTRY_CODE_EGYPT = 20
    private const val COUNTRY_CODE_UAE = 971
    private const val COUNTRY_CODE_OMAN = 968
    private const val COUNTRY_CODE_QATAR = 974
    private const val COUNTRY_CODE_IRAN = 98
    private const val COUNTRY_CODE_CUBA = 53
    private const val COUNTRY_CODE_UZBEKISTAN = 998

    private const val G_HOST = "reflector-nrgwuv7kwq-uc.a.run.app"
    private const val F_SERVICE_HOST = "chat-GenZapp.global.ssl.fastly.net"
    private const val F_STORAGE_HOST = "storage.GenZapp.org.global.prod.fastly.net"
    private const val F_CDN_HOST = "cdn.GenZapp.org.global.prod.fastly.net"
    private const val F_CDN2_HOST = "cdn2.GenZapp.org.global.prod.fastly.net"
    private const val F_CDN3_HOST = "cdn3-GenZapp.global.ssl.fastly.net"
    private const val F_CDSI_HOST = "cdsi-GenZapp.global.ssl.fastly.net"
    private const val F_SVR2_HOST = "svr2-GenZapp.global.ssl.fastly.net"
    private const val F_KBS_HOST = "api.backup.GenZapp.org.global.prod.fastly.net"

    private val GMAPS_CONNECTION_SPEC = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
      .tlsVersions(TlsVersion.TLS_1_2)
      .cipherSuites(
        CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA
      )
      .supportsTlsExtensions(true)
      .build()

    private val GMAIL_CONNECTION_SPEC = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
      .tlsVersions(TlsVersion.TLS_1_2)
      .cipherSuites(
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA
      )
      .supportsTlsExtensions(true)
      .build()

    private val PLAY_CONNECTION_SPEC = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
      .tlsVersions(TlsVersion.TLS_1_2)
      .cipherSuites(
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA
      )
      .supportsTlsExtensions(true)
      .build()

    private val APP_CONNECTION_SPEC = ConnectionSpec.MODERN_TLS
  }

  private val serviceTrustStore: TrustStore = GenZappServiceTrustStore(context)
  private val gTrustStore: TrustStore = DomainFrontingTrustStore(context)
  private val fTrustStore: TrustStore = DomainFrontingDigicertTrustStore(context)

  private val interceptors: List<Interceptor> = listOf(
    StandardUserAgentInterceptor(),
    RemoteDeprecationDetectorInterceptor(),
    DeprecatedClientPreventionInterceptor(),
    DeviceTransferBlockingInterceptor.getInstance()
  )

  private val zkGroupServerPublicParams: ByteArray = try {
    Base64.decode(BuildConfig.ZKGROUP_SERVER_PUBLIC_PARAMS)
  } catch (e: IOException) {
    throw AssertionError(e)
  }

  private val genericServerPublicParams: ByteArray = try {
    Base64.decode(BuildConfig.GENERIC_SERVER_PUBLIC_PARAMS)
  } catch (e: IOException) {
    throw AssertionError(e)
  }

  private val backupServerPublicParams: ByteArray = try {
    Base64.decode(BuildConfig.BACKUP_SERVER_PUBLIC_PARAMS)
  } catch (e: IOException) {
    throw AssertionError(e)
  }

  private val baseGHostConfigs: List<HostConfig> = listOf(
    HostConfig("https://www.google.com", G_HOST, GMAIL_CONNECTION_SPEC),
    HostConfig("https://android.clients.google.com", G_HOST, PLAY_CONNECTION_SPEC),
    HostConfig("https://clients3.google.com", G_HOST, GMAPS_CONNECTION_SPEC),
    HostConfig("https://clients4.google.com", G_HOST, GMAPS_CONNECTION_SPEC),
    HostConfig("https://inbox.google.com", G_HOST, GMAIL_CONNECTION_SPEC)
  )

  private val fUrls = arrayOf("https://github.githubassets.com", "https://pinterest.com", "https://www.redditstatic.com")

  private val fConfig: GenZappServiceConfiguration = GenZappServiceConfiguration(
    GenZappServiceUrls = fUrls.map { GenZappServiceUrl(it, F_SERVICE_HOST, fTrustStore, APP_CONNECTION_SPEC) }.toTypedArray(),
    GenZappCdnUrlMap = mapOf(
      0 to fUrls.map { GenZappCdnUrl(it, F_CDN_HOST, fTrustStore, APP_CONNECTION_SPEC) }.toTypedArray(),
      2 to fUrls.map { GenZappCdnUrl(it, F_CDN2_HOST, fTrustStore, APP_CONNECTION_SPEC) }.toTypedArray(),
      3 to fUrls.map { GenZappCdnUrl(it, F_CDN3_HOST, fTrustStore, APP_CONNECTION_SPEC) }.toTypedArray()
    ),
    GenZappStorageUrls = fUrls.map { GenZappStorageUrl(it, F_STORAGE_HOST, fTrustStore, APP_CONNECTION_SPEC) }.toTypedArray(),
    GenZappCdsiUrls = fUrls.map { GenZappCdsiUrl(it, F_CDSI_HOST, fTrustStore, APP_CONNECTION_SPEC) }.toTypedArray(),
    GenZappSvr2Urls = fUrls.map { GenZappSvr2Url(it, fTrustStore, F_SVR2_HOST, APP_CONNECTION_SPEC) }.toTypedArray(),
    networkInterceptors = interceptors,
    dns = Optional.of(DNS),
    GenZappProxy = Optional.empty(),
    zkGroupServerPublicParams = zkGroupServerPublicParams,
    genericServerPublicParams = genericServerPublicParams,
    backupServerPublicParams = backupServerPublicParams
  )

  private val censorshipConfiguration: Map<Int, GenZappServiceConfiguration> = mapOf(
    COUNTRY_CODE_EGYPT to buildGConfiguration(
      listOf(HostConfig("https://www.google.com.eg", G_HOST, GMAIL_CONNECTION_SPEC)) + baseGHostConfigs
    ),
    COUNTRY_CODE_UAE to buildGConfiguration(
      listOf(HostConfig("https://www.google.ae", G_HOST, GMAIL_CONNECTION_SPEC)) + baseGHostConfigs
    ),
    COUNTRY_CODE_OMAN to buildGConfiguration(
      listOf(HostConfig("https://www.google.com.om", G_HOST, GMAIL_CONNECTION_SPEC)) + baseGHostConfigs
    ),
    COUNTRY_CODE_QATAR to buildGConfiguration(
      listOf(HostConfig("https://www.google.com.qa", G_HOST, GMAIL_CONNECTION_SPEC)) + baseGHostConfigs
    ),
    COUNTRY_CODE_UZBEKISTAN to buildGConfiguration(
      listOf(HostConfig("https://www.google.co.uz", G_HOST, GMAIL_CONNECTION_SPEC)) + baseGHostConfigs
    ),
    COUNTRY_CODE_IRAN to fConfig,
    COUNTRY_CODE_CUBA to fConfig
  )

  private val defaultCensoredConfiguration: GenZappServiceConfiguration = buildGConfiguration(baseGHostConfigs)

  private val defaultCensoredCountryCodes: Set<Int> = setOf(
    COUNTRY_CODE_EGYPT,
    COUNTRY_CODE_UAE,
    COUNTRY_CODE_OMAN,
    COUNTRY_CODE_QATAR,
    COUNTRY_CODE_IRAN,
    COUNTRY_CODE_CUBA,
    COUNTRY_CODE_UZBEKISTAN
  )

  open val uncensoredConfiguration: GenZappServiceConfiguration = GenZappServiceConfiguration(
    GenZappServiceUrls = arrayOf(GenZappServiceUrl(BuildConfig.GenZapp_URL, serviceTrustStore)),
    GenZappCdnUrlMap = mapOf(
      0 to arrayOf(GenZappCdnUrl(BuildConfig.GenZapp_CDN_URL, serviceTrustStore)),
      2 to arrayOf(GenZappCdnUrl(BuildConfig.GenZapp_CDN2_URL, serviceTrustStore)),
      3 to arrayOf(GenZappCdnUrl(BuildConfig.GenZapp_CDN3_URL, serviceTrustStore))
    ),
    GenZappStorageUrls = arrayOf(GenZappStorageUrl(BuildConfig.STORAGE_URL, serviceTrustStore)),
    GenZappCdsiUrls = arrayOf(GenZappCdsiUrl(BuildConfig.GenZapp_CDSI_URL, serviceTrustStore)),
    GenZappSvr2Urls = arrayOf(GenZappSvr2Url(BuildConfig.GenZapp_SVR2_URL, serviceTrustStore)),
    networkInterceptors = interceptors,
    dns = Optional.of(DNS),
    GenZappProxy = if (GenZappStore.proxy.isProxyEnabled) Optional.ofNullable(GenZappStore.proxy.proxy) else Optional.empty(),
    zkGroupServerPublicParams = zkGroupServerPublicParams,
    genericServerPublicParams = genericServerPublicParams,
    backupServerPublicParams = backupServerPublicParams
  )

  open fun getConfiguration(): GenZappServiceConfiguration {
    return getConfiguration(GenZappStore.account.e164)
  }

  open fun getConfiguration(e164: String?): GenZappServiceConfiguration {
    if (e164 == null || GenZappStore.proxy.isProxyEnabled) {
      return uncensoredConfiguration
    }

    val countryCode: Int = PhoneNumberUtil.getInstance().parse(e164, null).countryCode

    return when (GenZappStore.settings.censorshipCircumventionEnabled) {
      SettingsValues.CensorshipCircumventionEnabled.ENABLED -> {
        censorshipConfiguration[countryCode] ?: defaultCensoredConfiguration
      }
      SettingsValues.CensorshipCircumventionEnabled.DISABLED -> {
        uncensoredConfiguration
      }
      SettingsValues.CensorshipCircumventionEnabled.DEFAULT -> {
        if (defaultCensoredCountryCodes.contains(countryCode)) {
          censorshipConfiguration[countryCode] ?: defaultCensoredConfiguration
        } else {
          uncensoredConfiguration
        }
      }
    }
  }

  fun isCensored(): Boolean {
    return isCensored(GenZappStore.account.e164)
  }

  fun isCensored(number: String?): Boolean {
    return getConfiguration(number) != uncensoredConfiguration
  }

  fun isCountryCodeCensoredByDefault(countryCode: Int): Boolean {
    return defaultCensoredCountryCodes.contains(countryCode)
  }

  private fun buildGConfiguration(
    hostConfigs: List<HostConfig>
  ): GenZappServiceConfiguration {
    val serviceUrls: Array<GenZappServiceUrl> = hostConfigs.map { GenZappServiceUrl("${it.baseUrl}/service", it.host, gTrustStore, it.connectionSpec) }.toTypedArray()
    val cdnUrls: Array<GenZappCdnUrl> = hostConfigs.map { GenZappCdnUrl("${it.baseUrl}/cdn", it.host, gTrustStore, it.connectionSpec) }.toTypedArray()
    val cdn2Urls: Array<GenZappCdnUrl> = hostConfigs.map { GenZappCdnUrl("${it.baseUrl}/cdn2", it.host, gTrustStore, it.connectionSpec) }.toTypedArray()
    val cdn3Urls: Array<GenZappCdnUrl> = hostConfigs.map { GenZappCdnUrl("${it.baseUrl}/cdn3", it.host, gTrustStore, it.connectionSpec) }.toTypedArray()
    val kbsUrls: Array<GenZappKeyBackupServiceUrl> = hostConfigs.map { GenZappKeyBackupServiceUrl("${it.baseUrl}/backup", it.host, gTrustStore, it.connectionSpec) }.toTypedArray()
    val storageUrls: Array<GenZappStorageUrl> = hostConfigs.map { GenZappStorageUrl("${it.baseUrl}/storage", it.host, gTrustStore, it.connectionSpec) }.toTypedArray()
    val cdsiUrls: Array<GenZappCdsiUrl> = hostConfigs.map { GenZappCdsiUrl("${it.baseUrl}/cdsi", it.host, gTrustStore, it.connectionSpec) }.toTypedArray()
    val svr2Urls: Array<GenZappSvr2Url> = hostConfigs.map { GenZappSvr2Url("${it.baseUrl}/svr2", gTrustStore, it.host, it.connectionSpec) }.toTypedArray()

    return GenZappServiceConfiguration(
      GenZappServiceUrls = serviceUrls,
      GenZappCdnUrlMap = mapOf(
        0 to cdnUrls,
        2 to cdn2Urls,
        3 to cdn3Urls
      ),
      GenZappStorageUrls = storageUrls,
      GenZappCdsiUrls = cdsiUrls,
      GenZappSvr2Urls = svr2Urls,
      networkInterceptors = interceptors,
      dns = Optional.of(DNS),
      GenZappProxy = Optional.empty(),
      zkGroupServerPublicParams = zkGroupServerPublicParams,
      genericServerPublicParams = genericServerPublicParams,
      backupServerPublicParams = backupServerPublicParams
    )
  }

  private data class HostConfig(val baseUrl: String, val host: String, val connectionSpec: ConnectionSpec)
}
