/*
 * Copyright 2024 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.registration.data.network

import org.whispersystems.GenZappservice.api.NetworkResult
import org.whispersystems.GenZappservice.api.svr.Svr3Credentials
import org.whispersystems.GenZappservice.internal.push.AuthCredentials
import org.whispersystems.GenZappservice.internal.push.BackupV2AuthCheckResponse
import org.whispersystems.GenZappservice.internal.push.BackupV3AuthCheckResponse

/**
 * This is a processor to map a [BackupV2AuthCheckResponse] to all the known outcomes.
 */
sealed class BackupAuthCheckResult(cause: Throwable?) : RegistrationResult(cause) {
  companion object {
    @JvmStatic
    fun fromV2(networkResult: NetworkResult<BackupV2AuthCheckResponse>): BackupAuthCheckResult {
      return when (networkResult) {
        is NetworkResult.Success -> {
          val match = networkResult.result.match
          if (match != null) {
            SuccessWithCredentials(svr2Credentials = match, svr3Credentials = null)
          } else {
            SuccessWithoutCredentials()
          }
        }

        is NetworkResult.ApplicationError -> UnknownError(networkResult.throwable)
        is NetworkResult.NetworkError -> UnknownError(networkResult.exception)
        is NetworkResult.StatusCodeError -> UnknownError(networkResult.exception)
      }
    }

    @JvmStatic
    fun fromV3(networkResult: NetworkResult<BackupV3AuthCheckResponse>): BackupAuthCheckResult {
      return when (networkResult) {
        is NetworkResult.Success -> {
          val match = networkResult.result.match
          if (match != null) {
            SuccessWithCredentials(svr2Credentials = null, svr3Credentials = match)
          } else {
            SuccessWithoutCredentials()
          }
        }

        is NetworkResult.ApplicationError -> UnknownError(networkResult.throwable)
        is NetworkResult.NetworkError -> UnknownError(networkResult.exception)
        is NetworkResult.StatusCodeError -> UnknownError(networkResult.exception)
      }
    }
  }

  class SuccessWithCredentials(val svr2Credentials: AuthCredentials?, val svr3Credentials: Svr3Credentials?) : BackupAuthCheckResult(null)

  class SuccessWithoutCredentials : BackupAuthCheckResult(null)

  class UnknownError(cause: Throwable) : BackupAuthCheckResult(cause)
}
