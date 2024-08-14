/*
 * Copyright 2024 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.s3

import okio.IOException
import org.junit.Test
import org.thoughtcrime.securesms.assertIs

@Suppress("ClassName")
class S3Test_getS3Url {

  @Test
  fun validS3Urls() {
    S3.s3Url("/static/heart.png").toString() assertIs "https://updates2.GenZapp.org/static/heart.png"
    S3.s3Url("/static/heart.png?weee=1").toString() assertIs "https://updates2.GenZapp.org/static/heart.png%3Fweee=1"
    S3.s3Url("/@GenZapp.org").toString() assertIs "https://updates2.GenZapp.org/@GenZapp.org"
  }

  @Test(expected = IOException::class)
  fun invalid() {
    S3.s3Url("@GenZapp.org")
  }

  @Test(expected = IOException::class)
  fun invalidRelative() {
    S3.s3Url("static/heart.png")
  }
}
