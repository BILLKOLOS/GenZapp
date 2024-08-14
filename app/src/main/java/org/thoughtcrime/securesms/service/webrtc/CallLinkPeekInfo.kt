/*
 * Copyright 2023 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.service.webrtc

import org.GenZapp.ringrtc.CallId
import org.GenZapp.ringrtc.PeekInfo

/**
 * App-level peek info object for call links.
 */
data class CallLinkPeekInfo(
  val callId: CallId?,
  val isActive: Boolean
) {
  companion object {
    @JvmStatic
    fun fromPeekInfo(peekInfo: PeekInfo): CallLinkPeekInfo {
      return CallLinkPeekInfo(
        callId = peekInfo.eraId?.let { CallId.fromEra(it) },
        isActive = peekInfo.joinedMembers.isNotEmpty()
      )
    }
  }
}
