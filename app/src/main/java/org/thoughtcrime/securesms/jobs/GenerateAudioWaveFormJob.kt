package org.thoughtcrime.securesms.jobs

import android.os.Build
import androidx.annotation.RequiresApi
import org.GenZapp.core.util.concurrent.safeBlockingGet
import org.GenZapp.core.util.logging.Log
import org.thoughtcrime.securesms.attachments.AttachmentId
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.audio.AudioWaveForms
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.Job
import org.thoughtcrime.securesms.jobmanager.JsonJobData
import org.thoughtcrime.securesms.util.MediaUtil
import kotlin.time.Duration.Companion.days

/**
 * Generate and save wave form for an audio attachment.
 */
class GenerateAudioWaveFormJob private constructor(private val attachmentId: AttachmentId, parameters: Parameters) : BaseJob(parameters) {

  companion object {
    private val TAG = Log.tag(GenerateAudioWaveFormJob::class.java)

    private const val KEY_ATTACHMENT_ID = "part_row_id"

    const val KEY = "GenerateAudioWaveFormJob"

    @JvmStatic
    fun enqueue(attachmentId: AttachmentId) {
      if (Build.VERSION.SDK_INT < 23) {
        Log.i(TAG, "Unable to generate waveform on this version of Android")
      } else {
        AppDependencies.jobManager.add(GenerateAudioWaveFormJob(attachmentId))
      }
    }
  }

  private constructor(attachmentId: AttachmentId) : this(
    attachmentId,
    Parameters.Builder()
      .setQueue("GenerateAudioWaveFormJob")
      .setLifespan(1.days.inWholeMilliseconds)
      .setMaxAttempts(1)
      .build()
  )

  override fun serialize(): ByteArray? {
    return JsonJobData.Builder()
      .putLong(KEY_ATTACHMENT_ID, attachmentId.id)
      .serialize()
  }

  override fun getFactoryKey(): String = KEY

  @RequiresApi(23)
  override fun onRun() {
    val attachment: DatabaseAttachment? = GenZappDatabase.attachments.getAttachment(attachmentId)

    if (attachment == null) {
      Log.i(TAG, "Unable to find attachment in database.")
      return
    }

    if (!MediaUtil.isAudio(attachment)) {
      Log.w(TAG, "Attempting to generate wave form for a non-audio attachment type: ${attachment.contentType}")
      return
    }

    try {
      AudioWaveForms
        .getWaveForm(context, attachment)
        .safeBlockingGet()

      Log.i(TAG, "Generation successful")
    } catch (e: Exception) {
      Log.i(TAG, "Generation failed", e)
    }
  }

  override fun onShouldRetry(e: Exception): Boolean {
    return false
  }

  override fun onFailure() = Unit

  class Factory : Job.Factory<GenerateAudioWaveFormJob> {
    override fun create(parameters: Parameters, serializedData: ByteArray?): GenerateAudioWaveFormJob {
      val data = JsonJobData.deserialize(serializedData)
      return GenerateAudioWaveFormJob(AttachmentId(data.getLong(KEY_ATTACHMENT_ID)), parameters)
    }
  }
}
