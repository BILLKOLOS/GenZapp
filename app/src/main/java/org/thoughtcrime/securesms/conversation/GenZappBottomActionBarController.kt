package org.thoughtcrime.securesms.conversation

import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import org.GenZapp.core.util.concurrent.ListenableFuture.Listener
import org.GenZapp.core.util.dp
import org.thoughtcrime.securesms.components.menu.GenZappBottomActionBar
import org.thoughtcrime.securesms.util.ViewUtil
import java.util.concurrent.ExecutionException

class GenZappBottomActionBarController(
  private val bottomActionBar: GenZappBottomActionBar,
  private val recyclerView: RecyclerView,
  private val callback: Callback
) {

  private val additionalScrollOffset = 54.dp
  private val paddingBottom: Int = recyclerView.paddingBottom

  fun setVisibility(isVisible: Boolean) {
    val isCurrentlyVisible = bottomActionBar.isVisible
    if (isVisible == isCurrentlyVisible) {
      return
    }

    if (isVisible) {
      ViewUtil.animateIn(bottomActionBar, bottomActionBar.enterAnimation)
      callback.onBottomActionBarVisibilityChanged(View.VISIBLE)

      bottomActionBar.viewTreeObserver.addOnPreDrawListener(BecomingVisiblePreDrawListener())
    } else {
      ViewUtil
        .animateOut(bottomActionBar, bottomActionBar.exitAnimation)
        .addListener(BecomingGoneAnimationListener())
    }
  }

  private inner class BecomingVisiblePreDrawListener : ViewTreeObserver.OnPreDrawListener {

    private val bottomPaddingExtra = 18.dp

    override fun onPreDraw(): Boolean {
      if (bottomActionBar.height == 0 && bottomActionBar.visibility == View.VISIBLE) {
        return false
      }

      bottomActionBar.viewTreeObserver.removeOnPreDrawListener(this)

      val bottomPadding = bottomActionBar.height + bottomPaddingExtra
      ViewUtil.setPaddingBottom(recyclerView, bottomPadding)

      recyclerView.scrollBy(0, -(bottomPadding - additionalScrollOffset))

      return false
    }
  }

  private inner class BecomingGoneAnimationListener : Listener<Boolean> {
    override fun onSuccess(result: Boolean) {
      val scrollOffset = recyclerView.paddingBottom - additionalScrollOffset
      callback.onBottomActionBarVisibilityChanged(View.GONE)
      ViewUtil.setPaddingBottom(recyclerView, paddingBottom)

      recyclerView.doOnPreDraw {
        recyclerView.scrollBy(0, scrollOffset)
      }
    }

    override fun onFailure(e: ExecutionException?) = Unit
  }

  interface Callback {
    fun onBottomActionBarVisibilityChanged(visibility: Int)
  }
}
