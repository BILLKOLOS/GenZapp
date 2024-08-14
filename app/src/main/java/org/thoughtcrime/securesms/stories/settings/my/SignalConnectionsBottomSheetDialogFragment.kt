package org.thoughtcrime.securesms.stories.settings.my

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.FixedRoundedCornerBottomSheetDialogFragment
import org.thoughtcrime.securesms.util.SpanUtil

class GenZappConnectionsBottomSheetDialogFragment : FixedRoundedCornerBottomSheetDialogFragment() {

  override val peekHeightPercentage: Float = 1f

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
    val view = inflater.inflate(R.layout.stories_GenZapp_connection_bottom_sheet, container, false)
    view.findViewById<TextView>(R.id.text_1).text = SpanUtil.boldSubstring(getString(R.string.GenZappConnectionsBottomSheet__GenZapp_connections_are_people), getString(R.string.GenZappConnectionsBottomSheet___GenZapp_connections))
    return view
  }
}
