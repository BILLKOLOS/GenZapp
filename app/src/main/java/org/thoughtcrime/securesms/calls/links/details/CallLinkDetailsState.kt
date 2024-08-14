/**
 * Copyright 2023 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.calls.links.details

import androidx.compose.runtime.Immutable
import org.thoughtcrime.securesms.database.CallLinkTable

@Immutable
data class CallLinkDetailsState(
  val displayRevocationDialog: Boolean = false,
  val callLink: CallLinkTable.CallLink? = null
)
