package org.thoughtcrime.securesms.calls.log

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.GenZapp.core.util.concurrent.GenZappExecutors
import org.GenZapp.core.util.withinTransaction
import org.thoughtcrime.securesms.calls.links.UpdateCallLinkRepository
import org.thoughtcrime.securesms.database.CallLinkTable
import org.thoughtcrime.securesms.database.DatabaseObserver
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.CallLinkPeekJob
import org.thoughtcrime.securesms.jobs.CallLogEventSendJob
import org.thoughtcrime.securesms.service.webrtc.links.CallLinkRoomId
import org.thoughtcrime.securesms.service.webrtc.links.UpdateCallLinkResult

class CallLogRepository(
  private val updateCallLinkRepository: UpdateCallLinkRepository = UpdateCallLinkRepository(),
  private val callLogPeekHelper: CallLogPeekHelper
) : CallLogPagedDataSource.CallRepository {
  override fun getCallsCount(query: String?, filter: CallLogFilter): Int {
    return GenZappDatabase.calls.getCallsCount(query, filter)
  }

  override fun getCalls(query: String?, filter: CallLogFilter, start: Int, length: Int): List<CallLogRow> {
    return GenZappDatabase.calls.getCalls(start, length, query, filter)
  }

  override fun getCallLinksCount(query: String?, filter: CallLogFilter): Int {
    return when (filter) {
      CallLogFilter.MISSED -> 0
      CallLogFilter.ALL, CallLogFilter.AD_HOC -> GenZappDatabase.callLinks.getCallLinksCount(query)
    }
  }

  override fun getCallLinks(query: String?, filter: CallLogFilter, start: Int, length: Int): List<CallLogRow> {
    return when (filter) {
      CallLogFilter.MISSED -> emptyList()
      CallLogFilter.ALL, CallLogFilter.AD_HOC -> GenZappDatabase.callLinks.getCallLinks(query, start, length)
    }
  }

  override fun onCallTabPageLoaded(pageData: List<CallLogRow>) {
    GenZappExecutors.BOUNDED_IO.execute {
      callLogPeekHelper.onPageLoaded(pageData)
    }
  }

  fun markAllCallEventsRead() {
    GenZappExecutors.BOUNDED_IO.execute {
      val latestCall = GenZappDatabase.calls.getLatestCall() ?: return@execute
      GenZappDatabase.calls.markAllCallEventsRead()
      AppDependencies.jobManager.add(CallLogEventSendJob.forMarkedAsRead(latestCall))
    }
  }

  fun listenForChanges(): Observable<Unit> {
    return Observable.create { emitter ->
      fun refresh() {
        emitter.onNext(Unit)
      }

      val databaseObserver = DatabaseObserver.Observer {
        refresh()
      }

      AppDependencies.databaseObserver.registerConversationListObserver(databaseObserver)
      AppDependencies.databaseObserver.registerCallUpdateObserver(databaseObserver)

      emitter.setCancellable {
        AppDependencies.databaseObserver.unregisterObserver(databaseObserver)
      }
    }
  }

  fun deleteSelectedCallLogs(
    selectedCallRowIds: Set<Long>
  ): Completable {
    return Completable.fromAction {
      GenZappDatabase.calls.deleteNonAdHocCallEvents(selectedCallRowIds)
    }.subscribeOn(Schedulers.io())
  }

  fun deleteAllCallLogsExcept(
    selectedCallRowIds: Set<Long>,
    missedOnly: Boolean
  ): Completable {
    return Completable.fromAction {
      GenZappDatabase.calls.deleteAllNonAdHocCallEventsExcept(selectedCallRowIds, missedOnly)
    }.subscribeOn(Schedulers.io())
  }

  /**
   * Delete all call events / unowned links and enqueue clear history job, and then
   * emit a clear history message.
   *
   * This explicitly drops failed call link revocations of call links, and those call links
   * will remain visible to the user. This is safe because the clear history sync message should
   * only clear local history and then poll link status from the server.
   */
  fun deleteAllCallLogsOnOrBeforeNow(): Single<Int> {
    return Single.fromCallable {
      GenZappDatabase.rawDatabase.withinTransaction {
        val latestCall = GenZappDatabase.calls.getLatestCall() ?: return@withinTransaction
        GenZappDatabase.calls.deleteNonAdHocCallEventsOnOrBefore(latestCall.timestamp)
        GenZappDatabase.callLinks.deleteNonAdminCallLinksOnOrBefore(latestCall.timestamp)
        AppDependencies.jobManager.add(CallLogEventSendJob.forClearHistory(latestCall))
      }

      GenZappDatabase.callLinks.getAllAdminCallLinksExcept(emptySet())
    }.flatMap(this::deleteAndCollectResults).map { 0 }.subscribeOn(Schedulers.io())
  }

  /**
   * Deletes the selected call links. We DELETE those links we don't have admin keys for,
   * and revoke the ones we *do* have admin keys for. We then perform a cleanup step on
   * terminate to clean up call events.
   */
  fun deleteSelectedCallLinks(
    selectedCallRowIds: Set<Long>,
    selectedRoomIds: Set<CallLinkRoomId>
  ): Single<Int> {
    return Single.fromCallable {
      val allCallLinkIds = GenZappDatabase.calls.getCallLinkRoomIdsFromCallRowIds(selectedCallRowIds) + selectedRoomIds
      GenZappDatabase.callLinks.deleteNonAdminCallLinks(allCallLinkIds)
      GenZappDatabase.callLinks.getAdminCallLinks(allCallLinkIds)
    }.flatMap(this::deleteAndCollectResults).subscribeOn(Schedulers.io())
  }

  /**
   * Deletes all but the selected call links. We DELETE those links we don't have admin keys for,
   * and revoke the ones we *do* have admin keys for. We then perform a cleanup step on
   * terminate to clean up call events.
   */
  fun deleteAllCallLinksExcept(
    selectedCallRowIds: Set<Long>,
    selectedRoomIds: Set<CallLinkRoomId>
  ): Single<Int> {
    return Single.fromCallable {
      val allCallLinkIds = GenZappDatabase.calls.getCallLinkRoomIdsFromCallRowIds(selectedCallRowIds) + selectedRoomIds
      GenZappDatabase.callLinks.deleteAllNonAdminCallLinksExcept(allCallLinkIds)
      GenZappDatabase.callLinks.getAllAdminCallLinksExcept(allCallLinkIds)
    }.flatMap(this::deleteAndCollectResults).subscribeOn(Schedulers.io())
  }

  private fun deleteAndCollectResults(callLinksToRevoke: Set<CallLinkTable.CallLink>): Single<Int> {
    return Single.merge(
      callLinksToRevoke.map {
        updateCallLinkRepository.deleteCallLink(it.credentials!!)
      }
    ).reduce(0) { acc, current ->
      acc + (if (current is UpdateCallLinkResult.Update) 0 else 1)
    }.doOnTerminate {
      GenZappDatabase.calls.updateAdHocCallEventDeletionTimestamps()
    }.doOnDispose {
      GenZappDatabase.calls.updateAdHocCallEventDeletionTimestamps()
    }
  }

  fun peekCallLinks(): Completable {
    return Completable.fromAction {
      val callLinks: List<CallLogRow.CallLink> = GenZappDatabase.callLinks.getCallLinks(
        query = null,
        offset = 0,
        limit = 10
      )

      val callEvents: List<CallLogRow.Call> = GenZappDatabase.calls.getCalls(
        offset = 0,
        limit = 10,
        searchTerm = null,
        filter = CallLogFilter.AD_HOC
      )

      val recipients = (callLinks.map { it.recipient } + callEvents.map { it.peer }).toSet()

      val jobs = recipients.take(10).map {
        CallLinkPeekJob(it.id)
      }

      AppDependencies.jobManager.addAll(jobs)
    }.subscribeOn(Schedulers.io())
  }
}
