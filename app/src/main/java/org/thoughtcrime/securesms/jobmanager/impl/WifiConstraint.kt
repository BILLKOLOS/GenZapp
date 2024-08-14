/*
 * Copyright 2024 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobmanager.impl

import android.app.Application
import android.app.job.JobInfo
import org.thoughtcrime.securesms.jobmanager.Constraint
import org.thoughtcrime.securesms.util.NetworkUtil

/**
 * Constraint that, when added, means that a job cannot be performed unless the user has Wifi
 */
class WifiConstraint(private val application: Application) : Constraint {

  companion object {
    const val KEY = "WifiConstraint"
  }

  override fun isMet(): Boolean {
    return NetworkUtil.isConnectedWifi(application)
  }

  override fun getFactoryKey(): String = KEY

  override fun applyToJobInfo(jobInfoBuilder: JobInfo.Builder) = Unit

  class Factory(val application: Application) : Constraint.Factory<WifiConstraint> {
    override fun create(): WifiConstraint {
      return WifiConstraint(application)
    }
  }
}
