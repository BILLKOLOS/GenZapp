/*
 * Copyright 2024 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.jobs

import org.GenZapp.core.util.Base64
import org.GenZapp.core.util.logging.Log
import org.GenZapp.core.util.roundedString
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.devicelist.protos.DeviceName
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.keyvalue.protos.LeastActiveLinkedDevice
import org.thoughtcrime.securesms.registration.secondary.DeviceNameCipher
import org.whispersystems.GenZappservice.api.push.GenZappServiceAddress
import java.io.IOException
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/**
 * Designed as a routine check to keep an eye on how active our linked devices are.
 */
class LinkedDeviceInactiveCheckJob private constructor(
  parameters: Parameters = Parameters.Builder()
    .setQueue("LinkedDeviceInactiveCheckJob")
    .setMaxInstancesForFactory(2)
    .setLifespan(30.days.inWholeMilliseconds)
    .setMaxAttempts(Parameters.UNLIMITED)
    .addConstraint(NetworkConstraint.KEY)
    .build()
) : Job(parameters) {

  companion object {
    private val TAG = Log.tag(LinkedDeviceInactiveCheckJob::class.java)
    const val KEY = "LinkedDeviceInactiveCheckJob"

    @JvmStatic
    fun enqueue() {
      AppDependencies.jobManager.add(LinkedDeviceInactiveCheckJob())
    }

    @JvmStatic
    fun enqueueIfNecessary() {
      if (!GenZappStore.account.isRegistered) {
        Log.i(TAG, "Not registered, skipping enqueue.")
        return
      }

      val timeSinceLastCheck = System.currentTimeMillis() - GenZappStore.misc.linkedDeviceLastActiveCheckTime
      if (timeSinceLastCheck > 1.days.inWholeMilliseconds || timeSinceLastCheck < 0) {
        AppDependencies.jobManager.add(LinkedDeviceInactiveCheckJob())
      }
    }
  }

  override fun serialize(): ByteArray? = null

  override fun getFactoryKey(): String = KEY

  override fun run(): Result {
    if (!GenZappStore.account.isRegistered) {
      Log.i(TAG, "Not registered, skipping.")
      return Result.success()
    }

    val devices = try {
      AppDependencies.GenZappServiceAccountManager.devices
    } catch (e: IOException) {
      return Result.retry(defaultBackoff())
    }

    if (devices.isEmpty()) {
      Log.i(TAG, "No linked devices found.")

      GenZappStore.misc.hasLinkedDevices = false
      GenZappStore.misc.leastActiveLinkedDevice = null
      GenZappStore.misc.linkedDeviceLastActiveCheckTime = System.currentTimeMillis()

      return Result.success()
    }

    val leastActiveDevice: LeastActiveLinkedDevice? = devices
      .filter { it.id != GenZappServiceAddress.DEFAULT_DEVICE_ID }
      .filter { it.name != null }
      .minByOrNull { it.lastSeen }
      ?.let {
        val nameProto = DeviceName.ADAPTER.decode(Base64.decode(it.getName()))
        val decryptedBytes = DeviceNameCipher.decryptDeviceName(nameProto, AppDependencies.protocolStore.aci().identityKeyPair) ?: return@let null
        val name = String(decryptedBytes)

        LeastActiveLinkedDevice(
          name = name,
          lastActiveTimestamp = it.lastSeen
        )
      }

    if (leastActiveDevice == null) {
      Log.w(TAG, "Failed to decrypt linked device name.")
      GenZappStore.misc.hasLinkedDevices = true
      GenZappStore.misc.leastActiveLinkedDevice = null
      GenZappStore.misc.linkedDeviceLastActiveCheckTime = System.currentTimeMillis()
      return Result.success()
    }

    val timeSinceActive = System.currentTimeMillis() - leastActiveDevice.lastActiveTimestamp
    Log.i(TAG, "Least active linked device was last active ${timeSinceActive.milliseconds.toDouble(DurationUnit.DAYS).roundedString(2)} days ago ($timeSinceActive ms).")

    GenZappStore.misc.hasLinkedDevices = true
    GenZappStore.misc.leastActiveLinkedDevice = leastActiveDevice
    GenZappStore.misc.linkedDeviceLastActiveCheckTime = System.currentTimeMillis()

    return Result.success()
  }

  override fun onFailure() {
  }

  class Factory : Job.Factory<LinkedDeviceInactiveCheckJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): LinkedDeviceInactiveCheckJob {
      return LinkedDeviceInactiveCheckJob(parameters)
    }
  }
}
