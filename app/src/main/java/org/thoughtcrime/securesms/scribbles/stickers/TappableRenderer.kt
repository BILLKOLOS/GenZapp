package org.thoughtcrime.securesms.scribbles.stickers

import org.GenZapp.imageeditor.core.Renderer

/**
 * A renderer that can handle a tap event
 */
interface TappableRenderer : Renderer {
  fun onTapped()
}
