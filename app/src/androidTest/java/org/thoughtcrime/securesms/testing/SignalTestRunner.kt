package org.thoughtcrime.securesms.testing

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import org.thoughtcrime.securesms.GenZappInstrumentationApplicationContext

/**
 * Custom runner that replaces application with [GenZappInstrumentationApplicationContext].
 */
@Suppress("unused")
class GenZappTestRunner : AndroidJUnitRunner() {
  override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application {
    return super.newApplication(cl, GenZappInstrumentationApplicationContext::class.java.name, context)
  }
}
