package org.thoughtcrime.securesms.contacts.sync

import android.content.Context
import androidx.annotation.WorkerThread
import org.GenZapp.contacts.SystemContactsRepository
import org.GenZapp.core.util.Stopwatch
import org.GenZapp.core.util.logging.Log
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.contacts.sync.FuzzyPhoneNumberHelper.InputResult
import org.thoughtcrime.securesms.contacts.sync.FuzzyPhoneNumberHelper.OutputResult
import org.thoughtcrime.securesms.database.RecipientTable.CdsV2Result
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.phonenumbers.PhoneNumberFormatter
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.util.RemoteConfig
import org.whispersystems.GenZappservice.api.push.exceptions.CdsiInvalidTokenException
import org.whispersystems.GenZappservice.api.push.exceptions.CdsiResourceExhaustedException
import org.whispersystems.GenZappservice.api.services.CdsiV2Service
import java.io.IOException
import java.util.Optional
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * Performs a CDS refresh using CDSv2.
 */
object ContactDiscoveryRefreshV2 {

  // Using Log.tag will cut off the version number
  private const val TAG = "CdsRefreshV2"

  /**
   * The maximum number items we will allow in a 'one-off' request.
   * One-off requests, while much faster, will always deduct the request size from our rate limit.
   * So we need to be careful about making it too large.
   * If a request size is over this limit, we will always fall back to a full sync.
   */
  private const val MAXIMUM_ONE_OFF_REQUEST_SIZE = 3

  @Throws(IOException::class)
  @WorkerThread
  @Synchronized
  @JvmStatic
  fun refreshAll(context: Context, timeoutMs: Long? = null): ContactDiscovery.RefreshResult {
    val recipientE164s: Set<String> = GenZappDatabase.recipients.getAllE164s().sanitize()
    val systemE164s: Set<String> = SystemContactsRepository.getAllDisplayNumbers(context).toE164s(context).sanitize()

    return refreshInternal(
      recipientE164s = recipientE164s,
      systemE164s = systemE164s,
      inputPreviousE164s = GenZappDatabase.cds.getAllE164s(),
      isPartialRefresh = false,
      timeoutMs = timeoutMs
    )
  }

  @Throws(IOException::class)
  @WorkerThread
  @Synchronized
  @JvmStatic
  fun refresh(context: Context, inputRecipients: List<Recipient>, timeoutMs: Long? = null): ContactDiscovery.RefreshResult {
    val recipients: List<Recipient> = inputRecipients.map { it.resolve() }
    val inputE164s: Set<String> = recipients.mapNotNull { it.e164.orElse(null) }.toSet().sanitize()

    return if (inputE164s.size > MAXIMUM_ONE_OFF_REQUEST_SIZE) {
      Log.i(TAG, "List of specific recipients to refresh is too large! (Size: ${recipients.size}). Doing a full refresh instead.")

      val fullResult: ContactDiscovery.RefreshResult = refreshAll(context, timeoutMs = timeoutMs)
      val inputIds: Set<RecipientId> = recipients.map { it.id }.toSet()

      ContactDiscovery.RefreshResult(
        registeredIds = fullResult.registeredIds.intersect(inputIds),
        rewrites = fullResult.rewrites.filterKeys { inputE164s.contains(it) }
      )
    } else {
      refreshInternal(
        recipientE164s = inputE164s,
        systemE164s = inputE164s,
        inputPreviousE164s = emptySet(),
        isPartialRefresh = true,
        timeoutMs = timeoutMs
      )
    }
  }

  @Throws(IOException::class)
  @WorkerThread
  @Synchronized
  fun lookupE164(e164: String): ContactDiscovery.LookupResult? {
    val response: CdsiV2Service.Response = try {
      AppDependencies.GenZappServiceAccountManager.getRegisteredUsersWithCdsi(
        emptySet(),
        setOf(e164),
        GenZappDatabase.recipients.getAllServiceIdProfileKeyPairs(),
        Optional.empty(),
        BuildConfig.CDSI_MRENCLAVE,
        10_000,
        if (RemoteConfig.useLibGenZappNetForCdsiLookup) AppDependencies.libGenZappNetwork else null
      ) {
        Log.i(TAG, "Ignoring token for one-off lookup.")
      }
    } catch (e: CdsiResourceExhaustedException) {
      Log.w(TAG, "CDS resource exhausted! Can try again in ${e.retryAfterSeconds} seconds.")
      GenZappStore.misc.cdsBlockedUtil = System.currentTimeMillis() + e.retryAfterSeconds.seconds.inWholeMilliseconds
      throw e
    } catch (e: CdsiInvalidTokenException) {
      Log.w(TAG, "We did not provide a token, but still got a token error! Unexpected, but ignoring.")
      throw e
    }

    return response.results[e164]?.let { item ->
      val id = GenZappDatabase.recipients.processIndividualCdsLookup(e164 = e164, aci = item.aci.orElse(null), pni = item.pni)

      ContactDiscovery.LookupResult(
        recipientId = id,
        pni = item.pni,
        aci = item.aci?.orElse(null)
      )
    }
  }

  @Throws(IOException::class)
  private fun refreshInternal(
    recipientE164s: Set<String>,
    systemE164s: Set<String>,
    inputPreviousE164s: Set<String>,
    isPartialRefresh: Boolean,
    timeoutMs: Long? = null
  ): ContactDiscovery.RefreshResult {
    val tag = "refreshInternal-v2"
    val stopwatch = Stopwatch(tag)

    val previousE164s: Set<String> = if (GenZappStore.misc.cdsToken != null && !isPartialRefresh) inputPreviousE164s else emptySet()

    val allE164s: Set<String> = recipientE164s + systemE164s
    val newRawE164s: Set<String> = allE164s - previousE164s
    val fuzzyInput: InputResult = FuzzyPhoneNumberHelper.generateInput(newRawE164s, recipientE164s)
    val newE164s: Set<String> = fuzzyInput.numbers

    if (newE164s.isEmpty() && previousE164s.isEmpty()) {
      Log.w(TAG, "[$tag] No data to send! Ignoring.")
      return ContactDiscovery.RefreshResult(emptySet(), emptyMap())
    }

    if (newE164s.size > RemoteConfig.cdsHardLimit) {
      Log.w(TAG, "[$tag] Number of new contacts (${newE164s.size.roundedString()} > hard limit (${RemoteConfig.cdsHardLimit}! Failing and marking ourselves as permanently blocked.")
      GenZappStore.misc.markCdsPermanentlyBlocked()
      throw IOException("New contacts over the CDS hard limit!")
    }

    val token: ByteArray? = if (previousE164s.isNotEmpty() && !isPartialRefresh) GenZappStore.misc.cdsToken else null

    stopwatch.split("preamble")

    val response: CdsiV2Service.Response = try {
      AppDependencies.GenZappServiceAccountManager.getRegisteredUsersWithCdsi(
        previousE164s,
        newE164s,
        GenZappDatabase.recipients.getAllServiceIdProfileKeyPairs(),
        Optional.ofNullable(token),
        BuildConfig.CDSI_MRENCLAVE,
        timeoutMs,
        if (RemoteConfig.useLibGenZappNetForCdsiLookup) AppDependencies.libGenZappNetwork else null
      ) { tokenToSave ->
        stopwatch.split("network-pre-token")
        if (!isPartialRefresh) {
          GenZappStore.misc.cdsToken = tokenToSave
          GenZappDatabase.cds.updateAfterFullCdsQuery(previousE164s + newE164s, allE164s + newE164s)
          Log.d(TAG, "Token saved!")
        } else {
          GenZappDatabase.cds.updateAfterPartialCdsQuery(newE164s)
          Log.d(TAG, "Ignoring token.")
        }
        stopwatch.split("cds-db")
      }
    } catch (e: CdsiResourceExhaustedException) {
      Log.w(TAG, "CDS resource exhausted! Can try again in ${e.retryAfterSeconds} seconds.")
      GenZappStore.misc.cdsBlockedUtil = System.currentTimeMillis() + e.retryAfterSeconds.seconds.inWholeMilliseconds
      throw e
    } catch (e: CdsiInvalidTokenException) {
      Log.w(TAG, "Our token was invalid! Only thing we can do now is clear our local state :(")
      GenZappStore.misc.cdsToken = null
      GenZappDatabase.cds.clearAll()
      throw e
    }

    if (!isPartialRefresh && GenZappStore.misc.isCdsBlocked) {
      Log.i(TAG, "Successfully made a request while blocked -- clearing blocked state.")
      GenZappStore.misc.clearCdsBlocked()
    }

    Log.d(TAG, "[$tag] Used ${response.quotaUsedDebugOnly} quota.")
    stopwatch.split("network-post-token")

    val registeredIds: MutableSet<RecipientId> = mutableSetOf()
    val rewrites: MutableMap<String, String> = mutableMapOf()

    val transformed: Map<String, CdsV2Result> = response.results.mapValues { entry -> CdsV2Result(entry.value.pni, entry.value.aci.orElse(null)) }
    val fuzzyOutput: OutputResult<CdsV2Result> = FuzzyPhoneNumberHelper.generateOutput(transformed, fuzzyInput)

    GenZappDatabase.recipients.rewritePhoneNumbers(fuzzyOutput.rewrites)
    stopwatch.split("rewrite-e164")

    registeredIds += GenZappDatabase.recipients.bulkProcessCdsResult(fuzzyOutput.numbers)
    rewrites += fuzzyOutput.rewrites
    stopwatch.split("process-result")

    val existingIds: Set<RecipientId> = GenZappDatabase.recipients.getAllPossiblyRegisteredByE164(recipientE164s + rewrites.values)
    stopwatch.split("get-ids")

    val inactiveIds: Set<RecipientId> = (existingIds - registeredIds).removePossiblyRegisteredButUndiscoverable()
    stopwatch.split("registered-but-unlisted")

    val missingFromCds: Set<RecipientId> = existingIds - registeredIds
    GenZappDatabase.recipients.updatePhoneNumberDiscoverability(registeredIds, missingFromCds)

    GenZappDatabase.recipients.bulkUpdatedRegisteredStatus(registeredIds, inactiveIds)
    stopwatch.split("update-registered")

    stopwatch.stop(TAG)

    return ContactDiscovery.RefreshResult(registeredIds, rewrites)
  }

  private fun hasCommunicatedWith(recipient: Recipient): Boolean {
    val localAci = GenZappStore.account.requireAci()
    return GenZappDatabase.threads.hasActiveThread(recipient.id) || (recipient.hasServiceId && GenZappDatabase.sessions.hasSessionFor(localAci, recipient.requireServiceId().toString()))
  }

  /**
   * If an account is undiscoverable, it won't come back in the CDS response. So just because we're missing a entry doesn't mean they've become unregistered.
   * This function removes people from the list that both have a serviceId and some history of communication. We consider this a good heuristic for
   * "maybe this person just removed themselves from CDS". We'll rely on profile fetches that occur during chat opens to check registered status and clear
   * actually-unregistered users out.
   */
  @WorkerThread
  private fun Set<RecipientId>.removePossiblyRegisteredButUndiscoverable(): Set<RecipientId> {
    val selfId = Recipient.self().id
    return this - Recipient.resolvedList(this)
      .filter {
        (it.hasServiceId && hasCommunicatedWith(it)) || it.id == selfId
      }
      .map { it.id }
      .toSet()
  }

  private fun Set<String>.toE164s(context: Context): Set<String> {
    return this.map { PhoneNumberFormatter.get(context).format(it) }.toSet()
  }

  private fun Set<String>.sanitize(): Set<String> {
    return this
      .filter {
        try {
          it.startsWith("+") && it.length > 1 && it[1] != '0' && it.toLong() > 0
        } catch (e: NumberFormatException) {
          false
        }
      }
      .toSet()
  }

  private fun Int.roundedString(): String {
    val nearestThousand = (this.toDouble() / 1000).roundToInt()
    return "~${nearestThousand}k"
  }
}
