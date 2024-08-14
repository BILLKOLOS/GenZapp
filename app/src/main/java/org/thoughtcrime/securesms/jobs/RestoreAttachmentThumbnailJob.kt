/*
 * Copyright 2023 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.thoughtcrime.securesms.jobs

import android.text.TextUtils
import androidx.annotation.VisibleForTesting
import org.GenZapp.core.util.logging.Log
import org.thoughtcrime.securesms.attachments.AttachmentId
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.backup.v2.BackupRepository
import org.thoughtcrime.securesms.backup.v2.BackupRepository.getThumbnailMediaName
import org.thoughtcrime.securesms.database.AttachmentTable
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JobLogger.format
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.mms.MmsException
import org.thoughtcrime.securesms.notifications.v2.ConversationId.Companion.forConversation
import org.thoughtcrime.securesms.transport.RetryLaterException
import org.thoughtcrime.securesms.util.RemoteConfig
import org.whispersystems.GenZappservice.api.messages.GenZappServiceAttachment
import org.whispersystems.GenZappservice.api.messages.GenZappServiceAttachmentPointer
import org.whispersystems.GenZappservice.api.messages.GenZappServiceAttachmentRemoteId
import org.whispersystems.GenZappservice.api.push.exceptions.PushNetworkException
import java.io.File
import java.io.IOException
import java.util.Optional
import java.util.concurrent.TimeUnit

/**
 * Download attachment from locations as specified in their record.
 */
class RestoreAttachmentThumbnailJob private constructor(
  parameters: Parameters,
  private val messageId: Long,
  attachmentId: AttachmentId
) : BaseJob(parameters) {

  companion object {
    const val KEY = "RestoreAttachmentThumbnailJob"
    val TAG = Log.tag(RestoreAttachmentThumbnailJob::class.java)

    private const val KEY_MESSAGE_ID = "message_id"
    private const val KEY_ATTACHMENT_ID = "part_row_id"

    @JvmStatic
    fun constructQueueString(attachmentId: AttachmentId): String {
      // TODO: decide how many queues
      return "RestoreAttachmentThumbnailJob"
    }
  }

  private val attachmentId: Long

  constructor(messageId: Long, attachmentId: AttachmentId, highPriority: Boolean = false) : this(
    Parameters.Builder()
      .setQueue(constructQueueString(attachmentId))
      .addConstraint(NetworkConstraint.KEY)
      .setLifespan(TimeUnit.DAYS.toMillis(1))
      .setMaxAttempts(Parameters.UNLIMITED)
      .setPriority(if (highPriority) Parameters.PRIORITY_HIGH else Parameters.PRIORITY_DEFAULT)
      .build(),
    messageId,
    attachmentId
  )

  init {
    this.attachmentId = attachmentId.id
  }

  override fun serialize(): ByteArray? {
    return JsonJobData.Builder()
      .putLong(KEY_MESSAGE_ID, messageId)
      .putLong(KEY_ATTACHMENT_ID, attachmentId)
      .serialize()
  }

  override fun getFactoryKey(): String {
    return KEY
  }

  override fun onAdded() {
    Log.i(TAG, "onAdded() messageId: $messageId  attachmentId: $attachmentId")

    val attachmentId = AttachmentId(attachmentId)
    val attachment = GenZappDatabase.attachments.getAttachment(attachmentId)
    val pending = attachment != null &&
      attachment.thumbnailRestoreState != AttachmentTable.ThumbnailRestoreState.FINISHED &&
      attachment.thumbnailRestoreState != AttachmentTable.ThumbnailRestoreState.PERMANENT_FAILURE &&
      attachment.thumbnailRestoreState != AttachmentTable.ThumbnailRestoreState.NONE
    if (pending) {
      Log.i(TAG, "onAdded() Marking thumbnail restore progress as 'started'")
      GenZappDatabase.attachments.setThumbnailTransferState(messageId, attachmentId, AttachmentTable.ThumbnailRestoreState.IN_PROGRESS)
    }
  }

  @Throws(Exception::class)
  public override fun onRun() {
    doWork()

    if (!GenZappDatabase.messages.isStory(messageId)) {
      AppDependencies.messageNotifier.updateNotification(context, forConversation(0))
    }
  }

  @Throws(IOException::class, RetryLaterException::class)
  fun doWork() {
    Log.i(TAG, "onRun() messageId: $messageId  attachmentId: $attachmentId")

    val attachmentId = AttachmentId(attachmentId)
    val attachment = GenZappDatabase.attachments.getAttachment(attachmentId)

    if (attachment == null) {
      Log.w(TAG, "attachment no longer exists.")
      return
    }

    downloadThumbnail(attachmentId, attachment)
  }

  override fun onFailure() {
    Log.w(TAG, format(this, "onFailure() thumbnail messageId: $messageId  attachmentId: $attachmentId "))

    val attachmentId = AttachmentId(attachmentId)
    markFailed(messageId, attachmentId)
  }

  override fun onShouldRetry(exception: Exception): Boolean {
    return exception is PushNetworkException ||
      exception is RetryLaterException
  }

  @Throws(InvalidPartException::class)
  private fun createThumbnailPointer(attachment: DatabaseAttachment): GenZappServiceAttachmentPointer {
    if (TextUtils.isEmpty(attachment.remoteKey)) {
      throw InvalidPartException("empty encrypted key")
    }

    val backupKey = GenZappStore.svr.getOrCreateMasterKey().deriveBackupKey()
    val backupDirectories = BackupRepository.getCdnBackupDirectories().successOrThrow()
    return try {
      val key = backupKey.deriveThumbnailTransitKey(attachment.getThumbnailMediaName())
      val mediaId = backupKey.deriveMediaId(attachment.getThumbnailMediaName()).encode()
      GenZappServiceAttachmentPointer(
        attachment.archiveThumbnailCdn,
        GenZappServiceAttachmentRemoteId.Backup(
          backupDir = backupDirectories.backupDir,
          mediaDir = backupDirectories.mediaDir,
          mediaId = mediaId
        ),
        null,
        key,
        Optional.empty(),
        Optional.empty(),
        0,
        0,
        Optional.empty(),
        Optional.empty(),
        attachment.incrementalMacChunkSize,
        Optional.empty(),
        attachment.voiceNote,
        attachment.borderless,
        attachment.videoGif,
        Optional.empty(),
        Optional.ofNullable(attachment.blurHash).map { it.hash },
        attachment.uploadTimestamp,
        attachment.uuid
      )
    } catch (e: IOException) {
      Log.w(TAG, e)
      throw InvalidPartException(e)
    } catch (e: ArithmeticException) {
      Log.w(TAG, e)
      throw InvalidPartException(e)
    }
  }

  private fun downloadThumbnail(attachmentId: AttachmentId, attachment: DatabaseAttachment) {
    if (attachment.thumbnailRestoreState == AttachmentTable.ThumbnailRestoreState.FINISHED) {
      Log.w(TAG, "$attachmentId already has thumbnail downloaded")
      return
    }
    if (attachment.thumbnailRestoreState == AttachmentTable.ThumbnailRestoreState.NONE) {
      Log.w(TAG, "$attachmentId has no thumbnail state")
      return
    }
    if (attachment.thumbnailRestoreState == AttachmentTable.ThumbnailRestoreState.PERMANENT_FAILURE) {
      Log.w(TAG, "$attachmentId thumbnail permanently failed")
      return
    }
    if (attachment.archiveMediaName == null) {
      Log.w(TAG, "$attachmentId was never archived! Cannot proceed.")
      return
    }

    val maxThumbnailSize: Long = RemoteConfig.maxAttachmentReceiveSizeBytes
    val thumbnailTransferFile: File = GenZappDatabase.attachments.createArchiveThumbnailTransferFile()
    val thumbnailFile: File = GenZappDatabase.attachments.createArchiveThumbnailTransferFile()

    val progressListener = object : GenZappServiceAttachment.ProgressListener {
      override fun onAttachmentProgress(total: Long, progress: Long) {
      }

      override fun shouldCancel(): Boolean {
        return this@RestoreAttachmentThumbnailJob.isCanceled
      }
    }

    val cdnCredentials = BackupRepository.getCdnReadCredentials(attachment.archiveCdn).successOrThrow().headers
    val messageReceiver = AppDependencies.GenZappServiceMessageReceiver
    val pointer = createThumbnailPointer(attachment)

    Log.w(TAG, "Downloading thumbnail for $attachmentId")
    val stream = messageReceiver
      .retrieveArchivedAttachment(
        GenZappStore.svr.getOrCreateMasterKey().deriveBackupKey().deriveMediaSecrets(attachment.getThumbnailMediaName()),
        cdnCredentials,
        thumbnailTransferFile,
        pointer,
        thumbnailFile,
        maxThumbnailSize,
        true,
        progressListener
      )

    GenZappDatabase.attachments.finalizeAttachmentThumbnailAfterDownload(attachmentId, attachment.archiveMediaId!!, stream, thumbnailTransferFile)
  }

  private fun markFailed(messageId: Long, attachmentId: AttachmentId) {
    try {
      GenZappDatabase.attachments.setThumbnailRestoreProgressFailed(attachmentId, messageId)
    } catch (e: MmsException) {
      Log.w(TAG, e)
    }
  }

  @VisibleForTesting
  internal class InvalidPartException : Exception {
    constructor(s: String?) : super(s)
    constructor(e: Exception?) : super(e)
  }

  class Factory : Job.Factory<RestoreAttachmentThumbnailJob?> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): RestoreAttachmentThumbnailJob {
      val data = JsonJobData.deserialize(serializedData)
      return RestoreAttachmentThumbnailJob(
        parameters = parameters,
        messageId = data.getLong(KEY_MESSAGE_ID),
        attachmentId = AttachmentId(data.getLong(KEY_ATTACHMENT_ID))
      )
    }
  }
}
