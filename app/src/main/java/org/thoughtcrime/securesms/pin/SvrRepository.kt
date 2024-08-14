/*
 * Copyright 2023 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.pin

import android.app.backup.BackupManager
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import org.GenZapp.core.util.Stopwatch
import org.GenZapp.core.util.logging.Log
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.JobTracker
import org.thoughtcrime.securesms.jobs.ReclaimUsernameAndLinkJob
import org.thoughtcrime.securesms.jobs.RefreshAttributesJob
import org.thoughtcrime.securesms.jobs.ResetSvrGuessCountJob
import org.thoughtcrime.securesms.jobs.StorageAccountRestoreJob
import org.thoughtcrime.securesms.jobs.StorageForcePushJob
import org.thoughtcrime.securesms.jobs.StorageSyncJob
import org.thoughtcrime.securesms.jobs.Svr2MirrorJob
import org.thoughtcrime.securesms.jobs.Svr3MirrorJob
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.lock.v2.PinKeyboardType
import org.thoughtcrime.securesms.megaphone.Megaphones
import org.thoughtcrime.securesms.registration.viewmodel.SvrAuthCredentialSet
import org.whispersystems.GenZappservice.api.SvrNoDataException
import org.whispersystems.GenZappservice.api.kbs.MasterKey
import org.whispersystems.GenZappservice.api.svr.SecureValueRecovery
import org.whispersystems.GenZappservice.api.svr.SecureValueRecovery.BackupResponse
import org.whispersystems.GenZappservice.api.svr.SecureValueRecovery.RestoreResponse
import org.whispersystems.GenZappservice.api.svr.SecureValueRecovery.SvrVersion
import org.whispersystems.GenZappservice.api.svr.Svr3Credentials
import org.whispersystems.GenZappservice.internal.push.AuthCredentials
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object SvrRepository {

  val TAG = Log.tag(SvrRepository::class.java)

  private val svr2: SecureValueRecovery = AppDependencies.GenZappServiceAccountManager.getSecureValueRecoveryV2(BuildConfig.SVR2_MRENCLAVE)
  private val svr3: SecureValueRecovery = AppDependencies.GenZappServiceAccountManager.getSecureValueRecoveryV3(AppDependencies.libGenZappNetwork)

  /** An ordered list of SVR implementations to read from. They should be in priority order, with the most important one listed first. */
  private val readImplementations: List<SecureValueRecovery> = if (Svr3Migration.shouldReadFromSvr3) listOf(svr3, svr2) else listOf(svr2)

  /** An ordered list of SVR implementations to write to. They should be in priority order, with the most important one listed first. */
  private val writeImplementations: List<SecureValueRecovery>
    get() {
      val implementations = mutableListOf<SecureValueRecovery>()
      if (Svr3Migration.shouldWriteToSvr3) {
        implementations += svr3
      }
      if (Svr3Migration.shouldWriteToSvr2) {
        implementations += svr2
      }
      return implementations
    }

  /**
   * A lock that ensures that only one thread at a time is altering the various pieces of SVR state.
   *
   * External usage of this should be limited to one-time migrations. Any routine operation that needs the lock should go in
   * this repository instead.
   */
  val operationLock = ReentrantLock()

  /**
   * Restores the master key from the first available SVR implementation available.
   *
   * This is intended to be called before registration has been completed, requiring
   * that you pass in the credentials provided during registration to access SVR.
   *
   * You could be hitting this because the user has reglock (and therefore need to
   * restore the master key before you can register), or you may be doing the
   * sms-skip flow.
   */
  @JvmStatic
  @WorkerThread
  @Throws(IOException::class, SvrWrongPinException::class, SvrNoDataException::class)
  fun restoreMasterKeyPreRegistration(credentials: SvrAuthCredentialSet, userPin: String): MasterKey {
    operationLock.withLock {
      Log.i(TAG, "restoreMasterKeyPreRegistration()", true)

      val operations: List<Pair<SecureValueRecovery, () -> RestoreResponse>> = if (Svr3Migration.shouldReadFromSvr3) {
        listOf(
          svr3 to { restoreMasterKeyPreRegistrationFromV3(credentials.svr3, userPin) },
          svr2 to { restoreMasterKeyPreRegistrationFromV2(credentials.svr2, userPin) }
        )
      } else {
        listOf(svr2 to { restoreMasterKeyPreRegistrationFromV2(credentials.svr2, userPin) })
      }

      for ((implementation, operation) in operations) {
        when (val response: RestoreResponse = operation()) {
          is RestoreResponse.Success -> {
            Log.i(TAG, "[restoreMasterKeyPreRegistration] Successfully restored master key. $implementation", true)

            when (implementation.svrVersion) {
              SvrVersion.SVR2 -> GenZappStore.svr.appendSvr2AuthTokenToList(response.authorization.asBasic())
              SvrVersion.SVR3 -> GenZappStore.svr.appendSvr3AuthTokenToList(response.authorization.asBasic())
            }

            return response.masterKey
          }

          is RestoreResponse.PinMismatch -> {
            Log.i(TAG, "[restoreMasterKeyPreRegistration] Incorrect PIN. $implementation", true)
            throw SvrWrongPinException(response.triesRemaining)
          }

          is RestoreResponse.NetworkError -> {
            Log.i(TAG, "[restoreMasterKeyPreRegistration] Network error. $implementation", response.exception, true)
            throw response.exception
          }

          is RestoreResponse.ApplicationError -> {
            Log.i(TAG, "[restoreMasterKeyPreRegistration] Application error. $implementation", response.exception, true)
            throw IOException(response.exception)
          }

          RestoreResponse.Missing -> {
            Log.w(TAG, "[restoreMasterKeyPreRegistration] No data found for $implementation | Continuing to next implementation.", true)
          }
        }
      }

      Log.w(TAG, "[restoreMasterKeyPreRegistration] No data found for any implementation!", true)

      throw SvrNoDataException()
    }
  }

  /**
   * Restores the master key from the first available SVR implementation available.
   *
   * This is intended to be called after the user has registered, allowing the function
   * to fetch credentials on its own.
   */
  @WorkerThread
  fun restoreMasterKeyPostRegistration(userPin: String, pinKeyboardType: PinKeyboardType): RestoreResponse {
    val stopwatch = Stopwatch("pin-submission")

    operationLock.withLock {
      for (implementation in readImplementations) {
        when (val response: RestoreResponse = implementation.restoreDataPostRegistration(userPin)) {
          is RestoreResponse.Success -> {
            Log.i(TAG, "[restoreMasterKeyPostRegistration] Successfully restored master key. $implementation", true)
            stopwatch.split("restore")

            GenZappStore.svr.setMasterKey(response.masterKey, userPin)
            GenZappStore.svr.isRegistrationLockEnabled = false
            GenZappStore.pin.resetPinReminders()
            GenZappStore.svr.isPinForgottenOrSkipped = false
            GenZappStore.storageService.setNeedsAccountRestore(false)
            GenZappStore.pin.keyboardType = pinKeyboardType
            GenZappStore.storageService.setNeedsAccountRestore(false)

            when (implementation.svrVersion) {
              SvrVersion.SVR2 -> GenZappStore.svr.appendSvr2AuthTokenToList(response.authorization.asBasic())
              SvrVersion.SVR3 -> GenZappStore.svr.appendSvr3AuthTokenToList(response.authorization.asBasic())
            }

            AppDependencies.jobManager.add(ResetSvrGuessCountJob())
            stopwatch.split("metadata")

            AppDependencies.jobManager.runSynchronously(StorageAccountRestoreJob(), StorageAccountRestoreJob.LIFESPAN)
            stopwatch.split("account-restore")

            AppDependencies
              .jobManager
              .startChain(StorageSyncJob())
              .then(ReclaimUsernameAndLinkJob())
              .enqueueAndBlockUntilCompletion(TimeUnit.SECONDS.toMillis(10))
            stopwatch.split("contact-restore")

            if (implementation.svrVersion != SvrVersion.SVR2 && Svr3Migration.shouldWriteToSvr2) {
              AppDependencies.jobManager.add(Svr2MirrorJob())
            }

            if (implementation.svrVersion != SvrVersion.SVR3 && Svr3Migration.shouldWriteToSvr3) {
              AppDependencies.jobManager.add(Svr3MirrorJob())
            }

            stopwatch.stop(TAG)

            return response
          }

          is RestoreResponse.PinMismatch -> {
            Log.i(TAG, "[restoreMasterKeyPostRegistration] Incorrect PIN. $implementation", true)
            return response
          }

          is RestoreResponse.NetworkError -> {
            Log.i(TAG, "[restoreMasterKeyPostRegistration] Network error. $implementation", response.exception, true)
            return response
          }

          is RestoreResponse.ApplicationError -> {
            Log.i(TAG, "[restoreMasterKeyPostRegistration] Application error. $implementation", response.exception, true)
            return response
          }

          RestoreResponse.Missing -> {
            Log.w(TAG, "[restoreMasterKeyPostRegistration] No data found for: $implementation | Continuing to next implementation.", true)
          }
        }
      }

      Log.w(TAG, "[restoreMasterKeyPostRegistration] No data found for any implementation!", true)
      return RestoreResponse.Missing
    }
  }

  /**
   * Sets the user's PIN to the one specified, updating local stores as necessary.
   * The resulting Single will not throw an error in any expected case, only if there's a runtime exception.
   */
  @WorkerThread
  @JvmStatic
  fun setPin(userPin: String, keyboardType: PinKeyboardType): BackupResponse {
    return operationLock.withLock {
      val masterKey: MasterKey = GenZappStore.svr.getOrCreateMasterKey()

      val writeTargets = writeImplementations

      val responses: List<BackupResponse> = writeTargets
        .map { it.setPin(userPin, masterKey) }
        .map { it.execute() }

      Log.i(TAG, "[setPin] Responses: $responses", true)

      val error: BackupResponse? = responses.map {
        when (it) {
          is BackupResponse.ApplicationError -> it
          BackupResponse.ExposeFailure -> it
          is BackupResponse.NetworkError -> it
          BackupResponse.ServerRejected -> it
          BackupResponse.EnclaveNotFound -> null
          is BackupResponse.Success -> null
        }
      }.firstOrNull()

      val overallResponse = error
        ?: responses.firstOrNull { it is BackupResponse.Success }
        ?: responses[0]

      if (overallResponse is BackupResponse.Success) {
        Log.i(TAG, "[setPin] Success!", true)

        GenZappStore.svr.setMasterKey(masterKey, userPin)
        GenZappStore.svr.isPinForgottenOrSkipped = false
        responses
          .filterIsInstance<BackupResponse.Success>()
          .forEach {
            when (it.svrVersion) {
              SvrVersion.SVR2 -> GenZappStore.svr.appendSvr2AuthTokenToList(it.authorization.asBasic())
              SvrVersion.SVR3 -> GenZappStore.svr.appendSvr3AuthTokenToList(it.authorization.asBasic())
            }
          }

        GenZappStore.pin.keyboardType = keyboardType
        GenZappStore.pin.resetPinReminders()

        AppDependencies.megaphoneRepository.markFinished(Megaphones.Event.PINS_FOR_ALL)

        AppDependencies.jobManager.add(RefreshAttributesJob())
      } else {
        Log.w(TAG, "[setPin] Failed to set PIN! $overallResponse", true)

        if (hasNoRegistrationLock) {
          GenZappStore.svr.onPinCreateFailure()
        }
      }

      overallResponse
    }
  }

  /**
   * Invoked after a user has successfully registered. Ensures all the necessary state is updated.
   */
  @WorkerThread
  @JvmStatic
  fun onRegistrationComplete(
    masterKey: MasterKey?,
    userPin: String?,
    hasPinToRestore: Boolean,
    setRegistrationLockEnabled: Boolean
  ) {
    Log.i(TAG, "[onRegistrationComplete] Starting", true)
    operationLock.withLock {
      if (masterKey == null && userPin != null) {
        error("If masterKey is present, pin must also be present!")
      }

      if (masterKey != null && userPin != null) {
        if (setRegistrationLockEnabled) {
          Log.i(TAG, "[onRegistrationComplete] Registration Lock", true)
          GenZappStore.svr.isRegistrationLockEnabled = true
        } else {
          Log.i(TAG, "[onRegistrationComplete] ReRegistration Skip SMS", true)
        }

        GenZappStore.svr.setMasterKey(masterKey, userPin)
        GenZappStore.pin.resetPinReminders()

        AppDependencies.jobManager.add(ResetSvrGuessCountJob())
      } else if (hasPinToRestore) {
        Log.i(TAG, "[onRegistrationComplete] Has a PIN to restore.", true)
        GenZappStore.svr.clearRegistrationLockAndPin()
        GenZappStore.storageService.setNeedsAccountRestore(true)
      } else {
        Log.i(TAG, "[onRegistrationComplete] No registration lock or PIN at all.", true)
        GenZappStore.svr.clearRegistrationLockAndPin()
      }
    }

    AppDependencies.jobManager.add(RefreshAttributesJob())
  }

  /**
   * Invoked when the user skips out on PIN restoration or otherwise fails to remember their PIN.
   */
  @JvmStatic
  fun onPinRestoreForgottenOrSkipped() {
    operationLock.withLock {
      GenZappStore.svr.clearRegistrationLockAndPin()
      GenZappStore.storageService.setNeedsAccountRestore(false)
      GenZappStore.svr.isPinForgottenOrSkipped = true
    }
  }

  @JvmStatic
  @WorkerThread
  fun optOutOfPin() {
    operationLock.withLock {
      GenZappStore.svr.optOut()

      AppDependencies.megaphoneRepository.markFinished(Megaphones.Event.PINS_FOR_ALL)

      bestEffortRefreshAttributes()
      bestEffortForcePushStorage()
    }
  }

  @JvmStatic
  @WorkerThread
  @Throws(IOException::class)
  fun enableRegistrationLockForUserWithPin() {
    operationLock.withLock {
      check(GenZappStore.svr.hasPin() && !GenZappStore.svr.hasOptedOut()) { "Must have a PIN to set a registration lock!" }

      Log.i(TAG, "[enableRegistrationLockForUserWithPin] Enabling registration lock.", true)
      AppDependencies.GenZappServiceAccountManager.enableRegistrationLock(GenZappStore.svr.getOrCreateMasterKey())
      GenZappStore.svr.isRegistrationLockEnabled = true
      Log.i(TAG, "[enableRegistrationLockForUserWithPin] Registration lock successfully enabled.", true)
    }
  }

  @JvmStatic
  @WorkerThread
  @Throws(IOException::class)
  fun disableRegistrationLockForUserWithPin() {
    operationLock.withLock {
      check(GenZappStore.svr.hasPin() && !GenZappStore.svr.hasOptedOut()) { "Must have a PIN to disable registration lock!" }

      Log.i(TAG, "[disableRegistrationLockForUserWithPin] Disabling registration lock.", true)
      AppDependencies.GenZappServiceAccountManager.disableRegistrationLock()
      GenZappStore.svr.isRegistrationLockEnabled = false
      Log.i(TAG, "[disableRegistrationLockForUserWithPin] Registration lock successfully disabled.", true)
    }
  }

  /**
   * Fetches new SVR credentials and persists them in the backup store to be used during re-registration.
   */
  @WorkerThread
  @Throws(IOException::class)
  fun refreshAndStoreAuthorization() {
    try {
      var newToken = if (Svr3Migration.shouldWriteToSvr3) {
        val credentials: AuthCredentials = svr3.authorization()
        GenZappStore.svr.appendSvr3AuthTokenToList(credentials.asBasic())
      } else {
        false
      }

      newToken = newToken || if (Svr3Migration.shouldWriteToSvr2) {
        val credentials: AuthCredentials = svr2.authorization()
        GenZappStore.svr.appendSvr2AuthTokenToList(credentials.asBasic())
      } else {
        false
      }

      if (newToken && GenZappStore.svr.hasPin()) {
        BackupManager(AppDependencies.application).dataChanged()
      }
    } catch (e: Throwable) {
      if (e is IOException) {
        throw e
      } else {
        throw IOException(e)
      }
    }
  }

  @WorkerThread
  @VisibleForTesting
  fun restoreMasterKeyPreRegistrationFromV2(credentials: AuthCredentials?, userPin: String): RestoreResponse {
    return if (credentials == null) {
      RestoreResponse.Missing
    } else {
      svr2.restoreDataPreRegistration(credentials, shareSet = null, userPin)
    }
  }

  @WorkerThread
  @VisibleForTesting
  fun restoreMasterKeyPreRegistrationFromV3(credentials: Svr3Credentials?, userPin: String): RestoreResponse {
    return if (credentials?.shareSet == null) {
      RestoreResponse.Missing
    } else {
      svr3.restoreDataPreRegistration(credentials.authCredentials, credentials.shareSet, userPin)
    }
  }

  @WorkerThread
  private fun bestEffortRefreshAttributes() {
    val result = AppDependencies.jobManager.runSynchronously(RefreshAttributesJob(), TimeUnit.SECONDS.toMillis(10))
    if (result.isPresent && result.get() == JobTracker.JobState.SUCCESS) {
      Log.i(TAG, "Attributes were refreshed successfully.", true)
    } else if (result.isPresent) {
      Log.w(TAG, "Attribute refresh finished, but was not successful. Enqueuing one for later. (Result: " + result.get() + ")", true)
      AppDependencies.jobManager.add(RefreshAttributesJob())
    } else {
      Log.w(TAG, "Job did not finish in the allotted time. It'll finish later.", true)
    }
  }

  @WorkerThread
  private fun bestEffortForcePushStorage() {
    val result = AppDependencies.jobManager.runSynchronously(StorageForcePushJob(), TimeUnit.SECONDS.toMillis(10))
    if (result.isPresent && result.get() == JobTracker.JobState.SUCCESS) {
      Log.i(TAG, "Storage was force-pushed successfully.", true)
    } else if (result.isPresent) {
      Log.w(TAG, "Storage force-pushed finished, but was not successful. Enqueuing one for later. (Result: " + result.get() + ")", true)
      AppDependencies.jobManager.add(RefreshAttributesJob())
    } else {
      Log.w(TAG, "Storage fore push did not finish in the allotted time. It'll finish later.", true)
    }
  }

  private val hasNoRegistrationLock: Boolean
    get() {
      return !GenZappStore.svr.isRegistrationLockEnabled &&
        !GenZappStore.svr.hasPin() &&
        !GenZappStore.svr.hasOptedOut()
    }
}
