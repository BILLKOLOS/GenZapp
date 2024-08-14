/**
 * Copyright 2023 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.service.webrtc.links

/**
 * Result type for call link updates.
 */
sealed interface UpdateCallLinkResult {
  data class Update(
    val state: GenZappCallLinkState
  ) : UpdateCallLinkResult

  data class Delete(
    val roomId: CallLinkRoomId
  ) : UpdateCallLinkResult

  data class Failure(
    val status: Short
  ) : UpdateCallLinkResult

  object NotAuthorized : UpdateCallLinkResult
}
