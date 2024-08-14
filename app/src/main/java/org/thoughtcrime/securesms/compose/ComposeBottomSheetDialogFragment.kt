package org.thoughtcrime.securesms.compose

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import org.GenZapp.core.ui.theme.GenZappTheme
import org.thoughtcrime.securesms.components.FixedRoundedCornerBottomSheetDialogFragment
import org.thoughtcrime.securesms.util.DynamicTheme

abstract class ComposeBottomSheetDialogFragment : FixedRoundedCornerBottomSheetDialogFragment() {

  protected open val forceDarkTheme = false

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    return ComposeView(requireContext()).apply {
      setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
      setContent {
        GenZappTheme(
          isDarkMode = forceDarkTheme || DynamicTheme.isDarkTheme(LocalContext.current)
        ) {
          Surface(
            shape = RoundedCornerShape(18.dp, 18.dp),
            color = GenZappTheme.colors.colorSurface1,
            contentColor = MaterialTheme.colorScheme.onSurface
          ) {
            SheetContent()
          }
        }
      }
    }
  }

  @Composable
  abstract fun SheetContent()
}
