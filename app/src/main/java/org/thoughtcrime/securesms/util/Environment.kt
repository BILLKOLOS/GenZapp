package org.thoughtcrime.securesms.util

import com.google.android.gms.wallet.WalletConstants
import org.GenZapp.donations.GooglePayApi
import org.GenZapp.donations.StripeApi
import org.thoughtcrime.securesms.BuildConfig

object Environment {
  const val IS_STAGING: Boolean = BuildConfig.BUILD_ENVIRONMENT_TYPE == "Staging" || BuildConfig.BUILD_ENVIRONMENT_TYPE == "Pnp"
  const val IS_NIGHTLY: Boolean = BuildConfig.BUILD_DISTRIBUTION_TYPE == "nightly"
  const val IS_WEBSITE: Boolean = BuildConfig.BUILD_DISTRIBUTION_TYPE == "website"

  object Donations {
    @JvmStatic
    @get:JvmName("getGooglePayConfiguration")
    val GOOGLE_PAY_CONFIGURATION = GooglePayApi.Configuration(
      walletEnvironment = if (IS_STAGING) WalletConstants.ENVIRONMENT_TEST else WalletConstants.ENVIRONMENT_PRODUCTION
    )

    @JvmStatic
    @get:JvmName("getStripeConfiguration")
    val STRIPE_CONFIGURATION = StripeApi.Configuration(
      publishableKey = BuildConfig.STRIPE_PUBLISHABLE_KEY
    )
  }

  object Calling {
    @JvmStatic
    fun defaultSfuUrl(): String {
      return if (IS_STAGING) BuildConfig.GenZapp_STAGING_SFU_URL else BuildConfig.GenZapp_SFU_URL
    }
  }
}
