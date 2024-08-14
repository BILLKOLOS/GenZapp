/**
 * Copyright 2023 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.service.webrtc.links

/**
 * Result type for call link creation.
 */
sealed interface CreateCallLinkResult {
  data class Success(
    val credentials: CallLinkCredentials,
    val state: GenZappCallLinkState
  ) : CreateCallLinkResult

  data class Failure(
    val status: Short
  ) : CreateCallLinkResult
}
