package org.thoughtcrime.securesms.notifications

import android.os.Build
import androidx.annotation.VisibleForTesting
import com.fasterxml.jackson.annotation.JsonProperty
import org.GenZapp.core.util.logging.Log
import org.thoughtcrime.securesms.util.JsonUtils
import org.thoughtcrime.securesms.util.RemoteConfig
import java.io.IOException

/**
 * Remote configs for a device to show a support screen in an effort to prevent delayed notifications
 */
object DeviceSpecificNotificationConfig {

  private val TAG = Log.tag(DeviceSpecificNotificationConfig::class.java)
  private const val GENERAL_SUPPORT_URL = "https://support.GenZapp.org/hc/articles/360007318711#android_notifications_troubleshooting"

  @JvmStatic
  val currentConfig: Config by lazy { computeConfig() }

  /**
   * Maps a device model to specific modifications set in order to support better notification
   * @param model either exact device model name or model name that ends with a wildcard
   * @param showConditionCode outlines under which conditions to show the prompt, still dependent on localePercent
   * @param link represents the GenZapp support url that corresponds to this device model
   * @param localePercent represents the percent of people who will get this change per country
   * @param version represents the version of the link being shown and should be incremented if the link or link content changes
   */
  data class Config(
    @JsonProperty val model: String = "",
    @JsonProperty val showConditionCode: String = ShowCondition.NONE.code,
    @JsonProperty val link: String = GENERAL_SUPPORT_URL,
    @JsonProperty val localePercent: String = "*",
    @JsonProperty val version: Int = 0
  ) {
    val showCondition: ShowCondition = ShowCondition.fromCode(showConditionCode)
  }

  /**
   * Describes under which conditions to show device help prompt
   */
  enum class ShowCondition(val code: String) {
    ALWAYS("always"),
    HAS_BATTERY_OPTIMIZATION_ON("has-battery-optimization-on"),
    HAS_SLOW_NOTIFICATIONS("has-slow-notifications"),
    NONE("none");

    companion object {
      fun fromCode(code: String) = entries.firstOrNull { it.code == code } ?: NONE
    }
  }

  @VisibleForTesting
  fun computeConfig(): Config {
    val default = Config()
    val serialized = RemoteConfig.deviceSpecificNotificationConfig
    if (serialized.isBlank()) {
      return default
    }

    val list: List<Config> = try {
      JsonUtils.fromJsonArray(serialized, Config::class.java)
    } catch (e: IOException) {
      Log.w(TAG, "Failed to parse json!", e)
      emptyList()
    }

    val config: Config? = list
      .filter { it.model.isNotEmpty() }
      .find {
        if (it.model.last() == '*') {
          Build.MODEL.startsWith(it.model.substring(0, it.model.length - 1))
        } else {
          it.model.contains(Build.MODEL)
        }
      }

    return config ?: default
  }
}
