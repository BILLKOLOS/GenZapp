/**
 * Copyright 2023 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.service.webrtc.links

import org.GenZapp.ringrtc.CallLinkState.Restrictions
import java.time.Instant

/**
 * Adapter class between our app code and RingRTC CallLinkState.
 */
data class GenZappCallLinkState(
  val name: String = "",
  val restrictions: Restrictions = Restrictions.UNKNOWN,
  @get:JvmName("hasBeenRevoked") val revoked: Boolean = false,
  val expiration: Instant = Instant.MAX
)
