/*
 * Copyright 2023 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.backup.v2.stream

import org.thoughtcrime.securesms.backup.v2.proto.Frame

interface BackupImportStream {
  fun read(): Frame?
}
