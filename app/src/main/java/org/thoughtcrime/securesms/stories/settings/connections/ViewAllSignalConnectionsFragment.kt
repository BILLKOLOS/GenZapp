package org.thoughtcrime.securesms.stories.settings.connections

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.ViewBinderDelegate
import org.thoughtcrime.securesms.components.WrapperDialogFragment
import org.thoughtcrime.securesms.contacts.LetterHeaderDecoration
import org.thoughtcrime.securesms.contacts.paged.ContactSearchAdapter
import org.thoughtcrime.securesms.contacts.paged.ContactSearchConfiguration
import org.thoughtcrime.securesms.contacts.paged.ContactSearchMediator
import org.thoughtcrime.securesms.databinding.ViewAllGenZappConnectionsFragmentBinding
import org.thoughtcrime.securesms.groups.SelectionLimits

class ViewAllGenZappConnectionsFragment : Fragment(R.layout.view_all_GenZapp_connections_fragment) {

  private val binding by ViewBinderDelegate(ViewAllGenZappConnectionsFragmentBinding::bind)

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    binding.recycler.addItemDecoration(LetterHeaderDecoration(requireContext()) { false })
    binding.toolbar.setNavigationOnClickListener {
      requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    val mediator = ContactSearchMediator(
      fragment = this,
      selectionLimits = SelectionLimits(0, 0),
      displayOptions = ContactSearchAdapter.DisplayOptions(
        displayCheckBox = false,
        displaySecondaryInformation = ContactSearchAdapter.DisplaySecondaryInformation.NEVER
      ),
      mapStateToConfiguration = { getConfiguration() },
      performSafetyNumberChecks = false
    )

    binding.recycler.adapter = mediator.adapter
  }

  private fun getConfiguration(): ContactSearchConfiguration {
    return ContactSearchConfiguration.build {
      addSection(
        ContactSearchConfiguration.Section.Individuals(
          includeHeader = false,
          includeSelf = false,
          includeLetterHeaders = true,
          transportType = ContactSearchConfiguration.TransportType.PUSH
        )
      )
    }
  }

  class Dialog : WrapperDialogFragment() {
    override fun getWrappedFragment(): Fragment {
      return ViewAllGenZappConnectionsFragment()
    }

    companion object {
      fun show(fragmentManager: FragmentManager) {
        Dialog().show(fragmentManager, null)
      }
    }
  }
}
