package org.thoughtcrime.securesms.stories

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Parcel
import android.os.Parcelable
import android.view.ContextThemeWrapper
import android.view.View
import androidx.core.graphics.scale
import androidx.core.view.drawToBitmap
import com.bumptech.glide.Glide
import com.bumptech.glide.load.Key
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.resource.SimpleResource
import org.GenZapp.core.util.Base64
import org.GenZapp.core.util.concurrent.safeBlockingGet
import org.GenZapp.core.util.readParcelableCompat
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.colors.ChatColors
import org.thoughtcrime.securesms.database.GenZappDatabase
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList
import org.thoughtcrime.securesms.database.model.databaseprotos.StoryTextPost
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.fonts.TextFont
import org.thoughtcrime.securesms.fonts.TextToScript
import org.thoughtcrime.securesms.fonts.TypefaceCache
import org.thoughtcrime.securesms.mms.DecryptableStreamUriLoader
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.util.ParcelUtil
import java.io.IOException
import java.security.MessageDigest

/**
 * Glide model to render a StoryTextPost as a bitmap
 */
data class StoryTextPostModel(
  private val storyTextPost: StoryTextPost,
  private val storySentAtMillis: Long,
  private val storyAuthor: RecipientId,
  private val bodyRanges: BodyRangeList?
) : Key, Parcelable {

  override fun updateDiskCacheKey(messageDigest: MessageDigest) {
    messageDigest.update(storyTextPost.encode())
    messageDigest.update(storySentAtMillis.toString().toByteArray())
    messageDigest.update(storyAuthor.serialize().toByteArray())
    messageDigest.update(bodyRanges?.encode() ?: ByteArray(0))
  }

  val text: String = storyTextPost.body

  fun getPlaceholder(): Drawable {
    return if (storyTextPost.background != null) {
      ChatColors.forChatColor(ChatColors.Id.NotSet, storyTextPost.background).chatBubbleMask
    } else {
      ColorDrawable(Color.TRANSPARENT)
    }
  }

  override fun writeToParcel(parcel: Parcel, flags: Int) {
    ParcelUtil.writeByteArray(parcel, storyTextPost.encode())
    parcel.writeLong(storySentAtMillis)
    parcel.writeParcelable(storyAuthor, flags)
    ParcelUtil.writeByteArray(parcel, bodyRanges?.encode())
  }

  override fun describeContents(): Int {
    return 0
  }

  companion object CREATOR : Parcelable.Creator<StoryTextPostModel> {
    override fun createFromParcel(parcel: Parcel): StoryTextPostModel {
      return StoryTextPostModel(
        storyTextPost = StoryTextPost.ADAPTER.decode(ParcelUtil.readByteArray(parcel)!!),
        storySentAtMillis = parcel.readLong(),
        storyAuthor = parcel.readParcelableCompat(RecipientId::class.java)!!,
        bodyRanges = ParcelUtil.readByteArray(parcel)?.let { BodyRangeList.ADAPTER.decode(it) }
      )
    }

    override fun newArray(size: Int): Array<StoryTextPostModel?> {
      return arrayOfNulls(size)
    }

    fun parseFrom(messageRecord: MessageRecord): StoryTextPostModel {
      return parseFrom(
        body = messageRecord.body,
        storySentAtMillis = messageRecord.timestamp,
        storyAuthor = messageRecord.fromRecipient.id,
        bodyRanges = messageRecord.messageRanges
      )
    }

    @JvmStatic
    @Throws(IOException::class)
    fun parseFrom(body: String, storySentAtMillis: Long, storyAuthor: RecipientId, bodyRanges: BodyRangeList?): StoryTextPostModel {
      return StoryTextPostModel(
        storyTextPost = StoryTextPost.ADAPTER.decode(Base64.decode(body)),
        storySentAtMillis = storySentAtMillis,
        storyAuthor = storyAuthor,
        bodyRanges = bodyRanges
      )
    }
  }

  class Decoder : ResourceDecoder<StoryTextPostModel, Bitmap> {

    companion object {
      private const val RENDER_HW_AR = 16f / 9f
    }

    override fun handles(source: StoryTextPostModel, options: Options): Boolean = true

    override fun decode(source: StoryTextPostModel, width: Int, height: Int, options: Options): Resource<Bitmap> {
      val message = GenZappDatabase.messages.getMessageFor(source.storySentAtMillis, source.storyAuthor).run {
        if (this is MmsMessageRecord) {
          this.withAttachments(GenZappDatabase.attachments.getAttachmentsForMessage(this.id))
        } else {
          this
        }
      }
      val view = StoryTextPostView(ContextThemeWrapper(AppDependencies.application, R.style.TextSecure_DarkNoActionBar))
      val typeface = TypefaceCache.get(
        AppDependencies.application,
        TextFont.fromStyle(source.storyTextPost.style),
        TextToScript.guessScript(source.storyTextPost.body)
      ).safeBlockingGet()

      val displayWidth: Int = AppDependencies.application.resources.displayMetrics.widthPixels
      val arHeight: Int = (RENDER_HW_AR * displayWidth).toInt()

      val linkPreview = (message as? MmsMessageRecord)?.linkPreviews?.firstOrNull()
      val useLargeThumbnail = source.text.isBlank()

      view.setTypeface(typeface)
      view.bindFromStoryTextPost(source.storySentAtMillis, source.storyTextPost, source.bodyRanges)
      view.bindLinkPreview(linkPreview, useLargeThumbnail, loadThumbnail = false)
      view.postAdjustLinkPreviewTranslationY()

      view.invalidate()
      view.measure(View.MeasureSpec.makeMeasureSpec(displayWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(arHeight, View.MeasureSpec.EXACTLY))
      view.layout(0, 0, view.measuredWidth, view.measuredHeight)

      val drawable = if (linkPreview != null && linkPreview.thumbnail.isPresent) {
        Glide
          .with(view)
          .load(DecryptableStreamUriLoader.DecryptableUri(linkPreview.thumbnail.get().uri!!))
          .centerCrop()
          .submit(view.getLinkPreviewThumbnailWidth(useLargeThumbnail), view.getLinkPreviewThumbnailHeight(useLargeThumbnail))
          .get()
      } else {
        null
      }

      view.setLinkPreviewDrawable(drawable, useLargeThumbnail)

      view.invalidate()
      view.measure(View.MeasureSpec.makeMeasureSpec(displayWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(arHeight, View.MeasureSpec.EXACTLY))
      view.layout(0, 0, view.measuredWidth, view.measuredHeight)

      val bitmap = view.drawToBitmap().scale(width, height)

      return SimpleResource(bitmap)
    }
  }
}
