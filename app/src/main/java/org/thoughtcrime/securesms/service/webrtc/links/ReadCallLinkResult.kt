/**
 * Copyright 2023 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.service.webrtc.links

/**
 * Result type for call link reads.
 */
sealed interface ReadCallLinkResult {
  data class Success(
    val callLinkState: GenZappCallLinkState
  ) : ReadCallLinkResult

  data class Failure(val status: Short) : ReadCallLinkResult
}
