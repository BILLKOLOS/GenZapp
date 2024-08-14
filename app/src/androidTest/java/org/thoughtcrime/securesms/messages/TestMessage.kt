package org.thoughtcrime.securesms.messages

import org.whispersystems.GenZappservice.api.crypto.EnvelopeMetadata
import org.whispersystems.GenZappservice.internal.push.Content
import org.whispersystems.GenZappservice.internal.push.Envelope

data class TestMessage(
  val envelope: Envelope,
  val content: Content,
  val metadata: EnvelopeMetadata,
  val serverDeliveredTimestamp: Long
)
