package org.thoughtcrime.securesms.database

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.FlakyTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.thoughtcrime.securesms.attachments.AttachmentId
import org.thoughtcrime.securesms.attachments.UriAttachment
import org.thoughtcrime.securesms.mms.MediaStream
import org.thoughtcrime.securesms.mms.SentMediaQuality
import org.thoughtcrime.securesms.providers.BlobProvider
import org.thoughtcrime.securesms.testing.assertIs
import org.thoughtcrime.securesms.testing.assertIsNot
import org.thoughtcrime.securesms.util.MediaUtil
import java.util.Optional

@RunWith(AndroidJUnit4::class)
class AttachmentTableTest {

  @Before
  fun setUp() {
    GenZappDatabase.attachments.deleteAllAttachments()
  }

  @Test
  fun givenABlob_whenIInsert2AttachmentsForPreUpload_thenIExpectDistinctIdsButSameFileName() {
    val blob = BlobProvider.getInstance().forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val highQualityProperties = createHighQualityTransformProperties()
    val highQualityImage = createAttachment(1, blob, highQualityProperties)
    val attachment = GenZappDatabase.attachments.insertAttachmentForPreUpload(highQualityImage)
    val attachment2 = GenZappDatabase.attachments.insertAttachmentForPreUpload(highQualityImage)

    assertNotEquals(attachment2.attachmentId, attachment.attachmentId)
    assertEquals(attachment2.fileName, attachment.fileName)
  }

  @FlakyTest
  @Test
  fun givenABlobAndDifferentTransformQuality_whenIInsert2AttachmentsForPreUpload_thenIExpectDifferentFileInfos() {
    val blob = BlobProvider.getInstance().forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val highQualityProperties = createHighQualityTransformProperties()
    val highQualityImage = createAttachment(1, blob, highQualityProperties)
    val lowQualityImage = createAttachment(1, blob, AttachmentTable.TransformProperties.empty())
    val attachment = GenZappDatabase.attachments.insertAttachmentForPreUpload(highQualityImage)
    val attachment2 = GenZappDatabase.attachments.insertAttachmentForPreUpload(lowQualityImage)

    GenZappDatabase.attachments.updateAttachmentData(
      attachment,
      createMediaStream(byteArrayOf(1, 2, 3, 4, 5))
    )

    GenZappDatabase.attachments.updateAttachmentData(
      attachment2,
      createMediaStream(byteArrayOf(1, 2, 3))
    )

    val attachment1Info = GenZappDatabase.attachments.getDataFileInfo(attachment.attachmentId)
    val attachment2Info = GenZappDatabase.attachments.getDataFileInfo(attachment2.attachmentId)

    assertNotEquals(attachment1Info, attachment2Info)
  }

  @FlakyTest
  @Ignore("test is flaky")
  @Test
  fun givenIdenticalAttachmentsInsertedForPreUpload_whenIUpdateAttachmentDataAndSpecifyOnlyModifyThisAttachment_thenIExpectDifferentFileInfos() {
    val blob = BlobProvider.getInstance().forData(byteArrayOf(1, 2, 3, 4, 5)).createForSingleSessionInMemory()
    val highQualityProperties = createHighQualityTransformProperties()
    val highQualityImage = createAttachment(1, blob, highQualityProperties)
    val attachment = GenZappDatabase.attachments.insertAttachmentForPreUpload(highQualityImage)
    val attachment2 = GenZappDatabase.attachments.insertAttachmentForPreUpload(highQualityImage)

    GenZappDatabase.attachments.updateAttachmentData(
      attachment,
      createMediaStream(byteArrayOf(1, 2, 3, 4, 5))
    )

    GenZappDatabase.attachments.updateAttachmentData(
      attachment2,
      createMediaStream(byteArrayOf(1, 2, 3, 4))
    )

    val attachment1Info = GenZappDatabase.attachments.getDataFileInfo(attachment.attachmentId)
    val attachment2Info = GenZappDatabase.attachments.getDataFileInfo(attachment2.attachmentId)

    assertNotEquals(attachment1Info, attachment2Info)
  }

  /**
   * Given: A previous attachment and two pre-upload attachments with the same data but different transform properties (standard and high).
   *
   * When changing content of standard pre-upload attachment to match pre-existing attachment
   *
   * Then update standard pre-upload attachment to match previous attachment, do not update high pre-upload attachment, and do
   * not delete shared pre-upload uri from disk as it is still being used by the high pre-upload attachment.
   */
  @Test
  fun doNotDeleteDedupedFileIfUsedByAnotherAttachmentWithADifferentTransformProperties() {
    // GIVEN
    val uncompressData = byteArrayOf(1, 2, 3, 4, 5)
    val compressedData = byteArrayOf(1, 2, 3)

    val blobUncompressed = BlobProvider.getInstance().forData(uncompressData).createForSingleSessionInMemory()

    val previousAttachment = createAttachment(1, BlobProvider.getInstance().forData(compressedData).createForSingleSessionInMemory(), AttachmentTable.TransformProperties.empty())
    val previousDatabaseAttachmentId: AttachmentId = GenZappDatabase.attachments.insertAttachmentsForMessage(1, listOf(previousAttachment), emptyList()).values.first()

    val standardQualityPreUpload = createAttachment(1, blobUncompressed, AttachmentTable.TransformProperties.empty())
    val standardDatabaseAttachment = GenZappDatabase.attachments.insertAttachmentForPreUpload(standardQualityPreUpload)

    val highQualityPreUpload = createAttachment(1, blobUncompressed, AttachmentTable.TransformProperties.forSentMediaQuality(Optional.empty(), SentMediaQuality.HIGH))
    val highDatabaseAttachment = GenZappDatabase.attachments.insertAttachmentForPreUpload(highQualityPreUpload)

    // WHEN
    GenZappDatabase.attachments.updateAttachmentData(standardDatabaseAttachment, createMediaStream(compressedData))

    // THEN
    val previousInfo = GenZappDatabase.attachments.getDataFileInfo(previousDatabaseAttachmentId)!!
    val standardInfo = GenZappDatabase.attachments.getDataFileInfo(standardDatabaseAttachment.attachmentId)!!
    val highInfo = GenZappDatabase.attachments.getDataFileInfo(highDatabaseAttachment.attachmentId)!!

    assertNotEquals(standardInfo, highInfo)
    highInfo.file assertIsNot standardInfo.file
    highInfo.file.exists() assertIs true
  }

  /**
   * Given: Three pre-upload attachments with the same data but different transform properties (1x standard and 2x high).
   *
   * When inserting content of high pre-upload attachment.
   *
   * Then do not deduplicate with standard pre-upload attachment, but do deduplicate second high insert.
   */
  @Test
  fun doNotDedupedFileIfUsedByAnotherAttachmentWithADifferentTransformProperties() {
    // GIVEN
    val uncompressData = byteArrayOf(1, 2, 3, 4, 5)
    val blobUncompressed = BlobProvider.getInstance().forData(uncompressData).createForSingleSessionInMemory()

    val standardQualityPreUpload = createAttachment(1, blobUncompressed, AttachmentTable.TransformProperties.empty())
    val standardDatabaseAttachment = GenZappDatabase.attachments.insertAttachmentForPreUpload(standardQualityPreUpload)

    // WHEN
    val highQualityPreUpload = createAttachment(1, blobUncompressed, AttachmentTable.TransformProperties.forSentMediaQuality(Optional.empty(), SentMediaQuality.HIGH))
    val highDatabaseAttachment = GenZappDatabase.attachments.insertAttachmentForPreUpload(highQualityPreUpload)

    val secondHighQualityPreUpload = createAttachment(1, blobUncompressed, AttachmentTable.TransformProperties.forSentMediaQuality(Optional.empty(), SentMediaQuality.HIGH))
    val secondHighDatabaseAttachment = GenZappDatabase.attachments.insertAttachmentForPreUpload(secondHighQualityPreUpload)

    // THEN
    val standardInfo = GenZappDatabase.attachments.getDataFileInfo(standardDatabaseAttachment.attachmentId)!!
    val highInfo = GenZappDatabase.attachments.getDataFileInfo(highDatabaseAttachment.attachmentId)!!
    val secondHighInfo = GenZappDatabase.attachments.getDataFileInfo(secondHighDatabaseAttachment.attachmentId)!!

    highInfo.file assertIsNot standardInfo.file
    secondHighInfo.file assertIs highInfo.file
    standardInfo.file.exists() assertIs true
    highInfo.file.exists() assertIs true
  }

  private fun createAttachment(id: Long, uri: Uri, transformProperties: AttachmentTable.TransformProperties): UriAttachment {
    return UriAttachmentBuilder.build(
      id,
      uri = uri,
      contentType = MediaUtil.IMAGE_JPEG,
      transformProperties = transformProperties
    )
  }

  private fun createHighQualityTransformProperties(): AttachmentTable.TransformProperties {
    return AttachmentTable.TransformProperties.forSentMediaQuality(Optional.empty(), SentMediaQuality.HIGH)
  }

  private fun createMediaStream(byteArray: ByteArray): MediaStream {
    return MediaStream(byteArray.inputStream(), MediaUtil.IMAGE_JPEG, 2, 2)
  }
}
