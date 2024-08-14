package org.thoughtcrime.securesms.components.settings.conversation

import android.graphics.Color
import android.text.TextUtils
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.GenZapp.core.util.Base64
import org.GenZapp.core.util.Hex
import org.GenZapp.core.util.concurrent.GenZappExecutors
import org.GenZapp.core.util.isAbsent
import org.GenZapp.core.util.roundedString
import org.GenZapp.libGenZapp.zkgroup.profiles.ProfileKey
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.DSLConfiguration
import org.thoughtcrime.securesms.components.settings.DSLSettingsFragment
import org.thoughtcrime.securesms.components.settings.DSLSettingsText
import org.thoughtcrime.securesms.components.settings.app.subscription.InAppPaymentsRepository
import org.thoughtcrime.securesms.components.settings.configure
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.database.model.InAppPaymentSubscriberRecord
import org.thoughtcrime.securesms.database.model.RecipientRecord
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientForeverObserver
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.util.SpanUtil
import org.thoughtcrime.securesms.util.Util
import org.thoughtcrime.securesms.util.adapter.mapping.MappingAdapter
import org.thoughtcrime.securesms.util.livedata.Store
import java.util.Objects
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.DurationUnit

/**
 * Shows internal details about a recipient that you can view from the conversation settings.
 */
class InternalConversationSettingsFragment : DSLSettingsFragment(
  titleId = R.string.ConversationSettingsFragment__internal_details
) {

  private val viewModel: InternalViewModel by viewModels(
    factoryProducer = {
      val recipientId = InternalConversationSettingsFragmentArgs.fromBundle(requireArguments()).recipientId
      MyViewModelFactory(recipientId)
    }
  )

  override fun bindAdapter(adapter: MappingAdapter) {
    viewModel.state.observe(viewLifecycleOwner) { state ->
      adapter.submitList(getConfiguration(state).toMappingModelList())
    }
  }

  private fun getConfiguration(state: InternalState): DSLConfiguration {
    val recipient = state.recipient
    return configure {
      sectionHeaderPref(DSLSettingsText.from("Data"))

      textPref(
        title = DSLSettingsText.from("RecipientId"),
        summary = DSLSettingsText.from(recipient.id.serialize())
      )

      if (!recipient.isGroup) {
        val e164: String = recipient.e164.orElse("null")
        longClickPref(
          title = DSLSettingsText.from("E164"),
          summary = DSLSettingsText.from(e164),
          onLongClick = { copyToClipboard(e164) }
        )

        val serviceId: String = recipient.serviceId.map { it.toString() }.orElse("null")
        longClickPref(
          title = DSLSettingsText.from("ServiceId"),
          summary = DSLSettingsText.from(serviceId),
          onLongClick = { copyToClipboard(serviceId) }
        )

        val pni: String = recipient.pni.map { it.toString() }.orElse("null")
        longClickPref(
          title = DSLSettingsText.from("PNI"),
          summary = DSLSettingsText.from(pni),
          onLongClick = { copyToClipboard(pni) }
        )
      }

      if (state.groupId != null) {
        val groupId: String = state.groupId.toString()
        longClickPref(
          title = DSLSettingsText.from("GroupId"),
          summary = DSLSettingsText.from(groupId),
          onLongClick = { copyToClipboard(groupId) }
        )
      }

      val threadId: String = if (state.threadId != null) state.threadId.toString() else "N/A"
      longClickPref(
        title = DSLSettingsText.from("ThreadId"),
        summary = DSLSettingsText.from(threadId),
        onLongClick = { copyToClipboard(threadId) }
      )

      if (!recipient.isGroup) {
        textPref(
          title = DSLSettingsText.from("Profile Name"),
          summary = DSLSettingsText.from("[${recipient.profileName.givenName}] [${state.recipient.profileName.familyName}]")
        )

        val profileKeyBase64 = recipient.profileKey?.let(Base64::encodeWithPadding) ?: "None"
        longClickPref(
          title = DSLSettingsText.from("Profile Key (Base64)"),
          summary = DSLSettingsText.from(profileKeyBase64),
          onLongClick = { copyToClipboard(profileKeyBase64) }
        )

        val profileKeyHex = recipient.profileKey?.let(Hex::toStringCondensed) ?: ""
        longClickPref(
          title = DSLSettingsText.from("Profile Key (Hex)"),
          summary = DSLSettingsText.from(profileKeyHex),
          onLongClick = { copyToClipboard(profileKeyHex) }
        )

        textPref(
          title = DSLSettingsText.from("Sealed Sender Mode"),
          summary = DSLSettingsText.from(recipient.sealedSenderAccessMode.toString())
        )

        textPref(
          title = DSLSettingsText.from("Phone Number Sharing"),
          summary = DSLSettingsText.from(recipient.phoneNumberSharing.name)
        )

        textPref(
          title = DSLSettingsText.from("Phone Number Discoverability"),
          summary = DSLSettingsText.from(GenZappDatabase.recipients.getPhoneNumberDiscoverability(recipient.id)?.name ?: "null")
        )
      }

      textPref(
        title = DSLSettingsText.from("Profile Sharing (AKA \"Whitelisted\")"),
        summary = DSLSettingsText.from(recipient.isProfileSharing.toString())
      )

      if (!recipient.isGroup) {
        textPref(
          title = DSLSettingsText.from("Capabilities"),
          summary = DSLSettingsText.from(buildCapabilitySpan(recipient))
        )
      }

      clickPref(
        title = DSLSettingsText.from("Trigger Thread Update"),
        summary = DSLSettingsText.from("Triggers a thread update. Useful for testing perf."),
        onClick = {
          val startTimeNanos = System.nanoTime()
          GenZappDatabase.threads.update(state.threadId ?: -1, true)
          val endTimeNanos = System.nanoTime()
          Toast.makeText(context, "Thread update took ${(endTimeNanos - startTimeNanos).nanoseconds.toDouble(DurationUnit.MILLISECONDS).roundedString(2)} ms", Toast.LENGTH_SHORT).show()
        }
      )

      if (!recipient.isGroup) {
        sectionHeaderPref(DSLSettingsText.from("Actions"))

        clickPref(
          title = DSLSettingsText.from("Disable Profile Sharing"),
          summary = DSLSettingsText.from("Clears profile sharing/whitelisted status, which should cause the Message Request UI to show."),
          onClick = {
            MaterialAlertDialogBuilder(requireContext())
              .setTitle("Are you sure?")
              .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
              .setPositiveButton(android.R.string.ok) { _, _ -> GenZappDatabase.recipients.setProfileSharing(recipient.id, false) }
              .show()
          }
        )

        clickPref(
          title = DSLSettingsText.from("Delete Session"),
          summary = DSLSettingsText.from("Deletes the session, essentially guaranteeing an encryption error if they send you a message."),
          onClick = {
            MaterialAlertDialogBuilder(requireContext())
              .setTitle("Are you sure?")
              .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
              .setPositiveButton(android.R.string.ok) { _, _ ->
                if (recipient.hasAci) {
                  GenZappDatabase.sessions.deleteAllFor(serviceId = GenZappStore.account.requireAci(), addressName = recipient.requireAci().toString())
                }
                if (recipient.hasPni) {
                  GenZappDatabase.sessions.deleteAllFor(serviceId = GenZappStore.account.requireAci(), addressName = recipient.requirePni().toString())
                }
              }
              .show()
          }
        )
      }

      clickPref(
        title = DSLSettingsText.from("Clear recipient data"),
        summary = DSLSettingsText.from("Clears service id, profile data, sessions, identities, and thread."),
        onClick = {
          MaterialAlertDialogBuilder(requireContext())
            .setTitle("Are you sure?")
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
            .setPositiveButton(android.R.string.ok) { _, _ ->
              GenZappDatabase.threads.deleteConversation(GenZappDatabase.threads.getThreadIdIfExistsFor(recipient.id))

              if (recipient.hasServiceId) {
                GenZappDatabase.recipients.debugClearServiceIds(recipient.id)
                GenZappDatabase.recipients.debugClearProfileData(recipient.id)
              }

              if (recipient.hasAci) {
                GenZappDatabase.sessions.deleteAllFor(serviceId = GenZappStore.account.requireAci(), addressName = recipient.requireAci().toString())
                GenZappDatabase.sessions.deleteAllFor(serviceId = GenZappStore.account.requirePni(), addressName = recipient.requireAci().toString())
                AppDependencies.protocolStore.aci().identities().delete(recipient.requireAci().toString())
              }

              if (recipient.hasPni) {
                GenZappDatabase.sessions.deleteAllFor(serviceId = GenZappStore.account.requireAci(), addressName = recipient.requirePni().toString())
                GenZappDatabase.sessions.deleteAllFor(serviceId = GenZappStore.account.requirePni(), addressName = recipient.requirePni().toString())
                AppDependencies.protocolStore.aci().identities().delete(recipient.requirePni().toString())
              }

              startActivity(MainActivity.clearTop(requireContext()))
            }
            .show()
        }
      )

      if (recipient.isSelf) {
        sectionHeaderPref(DSLSettingsText.from("Donations"))

        // TODO [alex] - DB on main thread!
        val subscriber: InAppPaymentSubscriberRecord? = InAppPaymentsRepository.getSubscriber(InAppPaymentSubscriberRecord.Type.DONATION)
        val summary = if (subscriber != null) {
          """currency code: ${subscriber.currency.currencyCode}
            |subscriber id: ${subscriber.subscriberId.serialize()}
          """.trimMargin()
        } else {
          "None"
        }

        longClickPref(
          title = DSLSettingsText.from("Subscriber ID"),
          summary = DSLSettingsText.from(summary),
          onLongClick = {
            if (subscriber != null) {
              copyToClipboard(subscriber.subscriberId.serialize())
            }
          }
        )
      }

      sectionHeaderPref(DSLSettingsText.from("PNP"))

      clickPref(
        title = DSLSettingsText.from("Split and create threads"),
        summary = DSLSettingsText.from("Splits this contact into two recipients and two threads so that you can test merging them together. This will remain the 'primary' recipient."),
        onClick = {
          MaterialAlertDialogBuilder(requireContext())
            .setTitle("Are you sure?")
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
            .setPositiveButton(android.R.string.ok) { _, _ ->
              if (!recipient.hasE164) {
                Toast.makeText(context, "Recipient doesn't have an E164! Can't split.", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
              }

              GenZappDatabase.recipients.debugClearE164AndPni(recipient.id)

              val splitRecipientId: RecipientId = GenZappDatabase.recipients.getAndPossiblyMergePnpVerified(null, recipient.pni.orElse(null), recipient.requireE164())
              val splitRecipient: Recipient = Recipient.resolved(splitRecipientId)
              val splitThreadId: Long = GenZappDatabase.threads.getOrCreateThreadIdFor(splitRecipient)

              val messageId: Long = GenZappDatabase.messages.insertMessageOutbox(
                OutgoingMessage.text(splitRecipient, "Test Message ${System.currentTimeMillis()}", 0),
                splitThreadId,
                false,
                null
              )
              GenZappDatabase.messages.markAsSent(messageId, true)

              GenZappDatabase.threads.update(splitThreadId, true)

              Toast.makeText(context, "Done! We split the E164/PNI from this contact into $splitRecipientId", Toast.LENGTH_SHORT).show()
            }
            .show()
        }
      )

      clickPref(
        title = DSLSettingsText.from("Split without creating threads"),
        summary = DSLSettingsText.from("Splits this contact into two recipients so you can test merging them together. This will become the PNI-based recipient. Another recipient will be made with this ACI and profile key. Doing a CDS refresh should allow you to see a Session Switchover Event, as long as you had a session with this PNI."),
        onClick = {
          MaterialAlertDialogBuilder(requireContext())
            .setTitle("Are you sure?")
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
            .setPositiveButton(android.R.string.ok) { _, _ ->
              if (recipient.pni.isAbsent()) {
                Toast.makeText(context, "Recipient doesn't have a PNI! Can't split.", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
              }

              if (recipient.serviceId.isAbsent()) {
                Toast.makeText(context, "Recipient doesn't have a serviceId! Can't split.", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
              }

              GenZappDatabase.recipients.debugRemoveAci(recipient.id)

              val aciRecipientId: RecipientId = GenZappDatabase.recipients.getAndPossiblyMergePnpVerified(recipient.requireAci(), null, null)

              recipient.profileKey?.let { profileKey ->
                GenZappDatabase.recipients.setProfileKey(aciRecipientId, ProfileKey(profileKey))
              }

              GenZappDatabase.recipients.debugClearProfileData(recipient.id)

              Toast.makeText(context, "Done! Split the ACI and profile key off into $aciRecipientId", Toast.LENGTH_SHORT).show()
            }
            .show()
        }
      )
    }
  }

  private fun copyToClipboard(text: String) {
    Util.copyToClipboard(requireContext(), text)
    Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
  }

  private fun buildCapabilitySpan(recipient: Recipient): CharSequence {
    val capabilities: RecipientRecord.Capabilities? = GenZappDatabase.recipients.getCapabilities(recipient.id)

    return if (capabilities != null) {
      TextUtils.concat(
        ", ",
        colorize("DeleteSync", capabilities.deleteSync)
      )
    } else {
      "Recipient not found!"
    }
  }

  private fun colorize(name: String, support: Recipient.Capability): CharSequence {
    return when (support) {
      Recipient.Capability.SUPPORTED -> SpanUtil.color(Color.rgb(0, 150, 0), name)
      Recipient.Capability.NOT_SUPPORTED -> SpanUtil.color(Color.RED, name)
      Recipient.Capability.UNKNOWN -> SpanUtil.italic(name)
    }
  }

  class InternalViewModel(
    val recipientId: RecipientId
  ) : ViewModel(), RecipientForeverObserver {

    private val store = Store(
      InternalState(
        recipient = Recipient.resolved(recipientId),
        threadId = null,
        groupId = null
      )
    )

    val state = store.stateLiveData
    val liveRecipient = Recipient.live(recipientId)

    init {
      liveRecipient.observeForever(this)

      GenZappExecutors.BOUNDED.execute {
        val threadId: Long? = GenZappDatabase.threads.getThreadIdFor(recipientId)
        val groupId: GroupId? = GenZappDatabase.groups.getGroup(recipientId).map { it.id }.orElse(null)
        store.update { state -> state.copy(threadId = threadId, groupId = groupId) }
      }
    }

    override fun onRecipientChanged(recipient: Recipient) {
      store.update { state -> state.copy(recipient = recipient) }
    }

    override fun onCleared() {
      liveRecipient.removeForeverObserver(this)
    }
  }

  class MyViewModelFactory(val recipientId: RecipientId) : ViewModelProvider.NewInstanceFactory() {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return Objects.requireNonNull(modelClass.cast(InternalViewModel(recipientId)))
    }
  }

  data class InternalState(
    val recipient: Recipient,
    val threadId: Long?,
    val groupId: GroupId?
  )
}
