package org.thoughtcrime.securesms.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.GenZapp.core.util.Base64
import org.GenZapp.core.util.update
import org.thoughtcrime.securesms.attachments.AttachmentId
import org.thoughtcrime.securesms.attachments.Cdn
import org.thoughtcrime.securesms.attachments.PointerAttachment
import org.thoughtcrime.securesms.backup.v2.BackupRepository.getMediaName
import org.thoughtcrime.securesms.database.AttachmentTable.TransformProperties
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.thoughtcrime.securesms.mms.MediaStream
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.mms.QuoteModel
import org.thoughtcrime.securesms.mms.SentMediaQuality
import org.thoughtcrime.securesms.providers.BlobProvider
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.Util
import org.whispersystems.GenZappservice.api.backup.MediaId
import org.whispersystems.GenZappservice.api.push.ServiceId
import java.io.File
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration.Companion.days

/**
 * Collection of [AttachmentTable] tests focused around deduping logic.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentTableTest_deduping {

  companion object {
    val DATA_A = byteArrayOf(1, 2, 3)
    val DATA_A_COMPRESSED = byteArrayOf(4, 5, 6)
    val DATA_A_HASH = byteArrayOf(1, 1, 1)

    val DATA_B = byteArrayOf(7, 8, 9)
  }

  @Before
  fun setUp() {
    GenZappStore.account.setAci(ServiceId.ACI.from(UUID.randomUUID()))
    GenZappStore.account.setPni(ServiceId.PNI.from(UUID.randomUUID()))
    GenZappStore.account.setE164("+15558675309")

    GenZappDatabase.attachments.deleteAllAttachments()
  }

  /**
   * Creates two different files with different data. Should not dedupe.
   */
  @Test
  fun differentFiles() {
    test {
      val id1 = insertWithData(DATA_A)
      val id2 = insertWithData(DATA_B)

      assertDataFilesAreDifferent(id1, id2)
    }
  }

  /**
   * Inserts files with identical data but with transform properties that make them incompatible. Should not dedupe.
   */
  @Test
  fun identicalFiles_incompatibleTransforms() {
    // Non-matching qualities
    test {
      val id1 = insertWithData(DATA_A, TransformProperties(sentMediaQuality = SentMediaQuality.STANDARD.code))
      val id2 = insertWithData(DATA_A, TransformProperties(sentMediaQuality = SentMediaQuality.HIGH.code))

      assertDataFilesAreDifferent(id1, id2)
      assertDataHashStartMatches(id1, id2)
    }

    // Non-matching video trim flag
    test {
      val id1 = insertWithData(DATA_A, TransformProperties())
      val id2 = insertWithData(DATA_A, TransformProperties(videoTrim = true))

      assertDataFilesAreDifferent(id1, id2)
      assertDataHashStartMatches(id1, id2)
    }

    // Non-matching video trim start time
    test {
      val id1 = insertWithData(DATA_A, TransformProperties(videoTrim = true, videoTrimStartTimeUs = 1, videoTrimEndTimeUs = 2))
      val id2 = insertWithData(DATA_A, TransformProperties(videoTrim = true, videoTrimStartTimeUs = 0, videoTrimEndTimeUs = 2))

      assertDataFilesAreDifferent(id1, id2)
      assertDataHashStartMatches(id1, id2)
    }

    // Non-matching video trim end time
    test {
      val id1 = insertWithData(DATA_A, TransformProperties(videoTrim = true, videoTrimStartTimeUs = 0, videoTrimEndTimeUs = 1))
      val id2 = insertWithData(DATA_A, TransformProperties(videoTrim = true, videoTrimStartTimeUs = 0, videoTrimEndTimeUs = 2))

      assertDataFilesAreDifferent(id1, id2)
      assertDataHashStartMatches(id1, id2)
    }

    // Non-matching mp4 fast start
    test {
      val id1 = insertWithData(DATA_A, TransformProperties(mp4FastStart = true))
      val id2 = insertWithData(DATA_A, TransformProperties(mp4FastStart = false))

      assertDataFilesAreDifferent(id1, id2)
      assertDataHashStartMatches(id1, id2)
    }
  }

  /**
   * Inserts files with identical data and compatible transform properties. Should dedupe.
   */
  @Test
  fun identicalFiles_compatibleTransforms() {
    test {
      val id1 = insertWithData(DATA_A)
      val id2 = insertWithData(DATA_A)

      assertDataFilesAreTheSame(id1, id2)
      assertDataHashStartMatches(id1, id2)
      assertSkipTransform(id1, false)
      assertSkipTransform(id2, false)
    }

    test {
      val id1 = insertWithData(DATA_A, TransformProperties(sentMediaQuality = SentMediaQuality.STANDARD.code))
      val id2 = insertWithData(DATA_A, TransformProperties(sentMediaQuality = SentMediaQuality.STANDARD.code))

      assertDataFilesAreTheSame(id1, id2)
      assertDataHashStartMatches(id1, id2)
      assertSkipTransform(id1, false)
      assertSkipTransform(id2, false)
    }

    test {
      val id1 = insertWithData(DATA_A, TransformProperties(sentMediaQuality = SentMediaQuality.HIGH.code))
      val id2 = insertWithData(DATA_A, TransformProperties(sentMediaQuality = SentMediaQuality.HIGH.code))

      assertDataFilesAreTheSame(id1, id2)
      assertDataHashStartMatches(id1, id2)
      assertSkipTransform(id1, false)
      assertSkipTransform(id2, false)
    }

    test {
      val id1 = insertWithData(DATA_A, TransformProperties(videoTrim = true, videoTrimStartTimeUs = 1, videoTrimEndTimeUs = 2))
      val id2 = insertWithData(DATA_A, TransformProperties(videoTrim = true, videoTrimStartTimeUs = 1, videoTrimEndTimeUs = 2))

      assertDataFilesAreTheSame(id1, id2)
      assertDataHashStartMatches(id1, id2)
      assertSkipTransform(id1, false)
      assertSkipTransform(id2, false)
    }
  }

  /**
   * Walks through various scenarios where files are compressed and uploaded.
   */
  @Test
  fun compressionAndUploads() {
    // Matches after the first is compressed, skip transform properly set
    test {
      val id1 = insertWithData(DATA_A)
      compress(id1, DATA_A_COMPRESSED)

      val id2 = insertWithData(DATA_A)

      assertDataFilesAreTheSame(id1, id2)
      assertDataHashStartMatches(id1, id2)
      assertSkipTransform(id1, true)
      assertSkipTransform(id2, true)
    }

    // Matches after the first is uploaded, skip transform and ending hash properly set
    test {
      val id1 = insertWithData(DATA_A)
      compress(id1, DATA_A_COMPRESSED)
      upload(id1)

      val id2 = insertWithData(DATA_A)

      assertDataFilesAreTheSame(id1, id2)
      assertDataHashStartMatches(id1, id2)
      assertDataHashEndMatches(id1, id2)
      assertSkipTransform(id1, true)
      assertSkipTransform(id2, true)
      assertRemoteFieldsMatch(id1, id2)
      assertArchiveFieldsMatch(id1, id2)
    }

    // Mimics sending two files at once. Ensures all fields are kept in sync as we compress and upload.
    test {
      val id1 = insertWithData(DATA_A)
      val id2 = insertWithData(DATA_A)

      assertDataFilesAreTheSame(id1, id2)
      assertDataHashStartMatches(id1, id2)
      assertSkipTransform(id1, false)
      assertSkipTransform(id2, false)

      compress(id1, DATA_A_COMPRESSED)

      assertDataFilesAreTheSame(id1, id2)
      assertDataHashStartMatches(id1, id2)
      assertSkipTransform(id1, true)
      assertSkipTransform(id2, true)

      upload(id1)

      assertDataFilesAreTheSame(id1, id2)
      assertDataHashStartMatches(id1, id2)
      assertDataHashEndMatches(id1, id2)
      assertRemoteFieldsMatch(id1, id2)
      assertArchiveFieldsMatch(id1, id2)
    }

    // Re-use the upload when uploaded recently
    test {
      val id1 = insertWithData(DATA_A)
      compress(id1, DATA_A_COMPRESSED)
      upload(id1, uploadTimestamp = System.currentTimeMillis())

      val id2 = insertWithData(DATA_A)

      assertDataFilesAreTheSame(id1, id2)
      assertDataHashStartMatches(id1, id2)
      assertDataHashEndMatches(id1, id2)
      assertRemoteFieldsMatch(id1, id2)
      assertArchiveFieldsMatch(id1, id2)
      assertSkipTransform(id1, true)
      assertSkipTransform(id2, true)
    }

    // Do not re-use old uploads
    test {
      val id1 = insertWithData(DATA_A)
      compress(id1, DATA_A_COMPRESSED)
      upload(id1, uploadTimestamp = System.currentTimeMillis() - 100.days.inWholeMilliseconds)

      val id2 = insertWithData(DATA_A)

      assertDataFilesAreTheSame(id1, id2)
      assertDataHashStartMatches(id1, id2)
      assertDataHashEndMatches(id1, id2)
      assertSkipTransform(id1, true)
      assertSkipTransform(id2, true)

      assertDoesNotHaveRemoteFields(id2)
      assertArchiveFieldsMatch(id1, id2)
    }

    // This isn't so much "desirable behavior" as it is documenting how things work.
    // If an attachment is compressed but not uploaded yet, it will have a DATA_HASH_START that doesn't match the actual file content.
    // This means that if we insert a new attachment with data that matches the compressed data, we won't find a match.
    // This is ok because we don't allow forwarding unsent messages, so the chances of the user somehow sending a file that matches data we compressed are very low.
    // What *is* more common is that the user may send DATA_A again, and in this case we will still catch the dedupe (which is already tested above).
    test {
      val id1 = insertWithData(DATA_A)
      compress(id1, DATA_A_COMPRESSED)

      val id2 = insertWithData(DATA_A_COMPRESSED)

      assertDataFilesAreDifferent(id1, id2)
    }

    // This represents what would happen if you forward an already-send compressed attachment. We should match, skip transform, and skip upload.
    test {
      val id1 = insertWithData(DATA_A)
      compress(id1, DATA_A_COMPRESSED)
      upload(id1, uploadTimestamp = System.currentTimeMillis())

      val id2 = insertWithData(DATA_A_COMPRESSED)

      assertDataFilesAreTheSame(id1, id2)
      assertDataHashEndMatches(id1, id2)
      assertSkipTransform(id1, true)
      assertSkipTransform(id1, true)
      assertRemoteFieldsMatch(id1, id2)
      assertArchiveFieldsMatch(id1, id2)
    }

    // This represents what would happen if you edited a video, sent it, then forwarded it. We should match, skip transform, and skip upload.
    test {
      val id1 = insertWithData(DATA_A, TransformProperties(videoTrim = true, videoTrimStartTimeUs = 1, videoTrimEndTimeUs = 2))
      compress(id1, DATA_A_COMPRESSED)
      upload(id1, uploadTimestamp = System.currentTimeMillis())

      val id2 = insertWithData(DATA_A_COMPRESSED)

      assertDataFilesAreTheSame(id1, id2)
      assertDataHashEndMatches(id1, id2)
      assertSkipTransform(id1, true)
      assertSkipTransform(id1, true)
      assertRemoteFieldsMatch(id1, id2)
      assertArchiveFieldsMatch(id1, id2)
    }

    // This represents what would happen if you edited a video, sent it, then forwarded it, but *edited the forwarded video*. We should not dedupe.
    test {
      val id1 = insertWithData(DATA_A, TransformProperties(videoTrim = true, videoTrimStartTimeUs = 1, videoTrimEndTimeUs = 2))
      compress(id1, DATA_A_COMPRESSED)
      upload(id1, uploadTimestamp = System.currentTimeMillis())

      val id2 = insertWithData(DATA_A_COMPRESSED, TransformProperties(videoTrim = true, videoTrimStartTimeUs = 1, videoTrimEndTimeUs = 2))

      assertDataFilesAreDifferent(id1, id2)
      assertSkipTransform(id1, true)
      assertSkipTransform(id2, false)
      assertDoesNotHaveRemoteFields(id2)
    }

    // This represents what would happen if you sent an image using standard quality, then forwarded it using high quality.
    // Since you're forwarding, it doesn't matter if the new thing has a higher quality, we should still match and skip transform.
    test {
      val id1 = insertWithData(DATA_A, TransformProperties(sentMediaQuality = SentMediaQuality.STANDARD.code))
      compress(id1, DATA_A_COMPRESSED)
      upload(id1, uploadTimestamp = System.currentTimeMillis())

      val id2 = insertWithData(DATA_A_COMPRESSED, TransformProperties(sentMediaQuality = SentMediaQuality.HIGH.code))

      assertDataFilesAreTheSame(id1, id2)
      assertDataHashEndMatches(id1, id2)
      assertSkipTransform(id1, true)
      assertSkipTransform(id1, true)
      assertRemoteFieldsMatch(id1, id2)
      assertArchiveFieldsMatch(id1, id2)
    }

    // This represents what would happen if you sent an image using high quality, then forwarded it using standard quality.
    // Since you're forwarding, it doesn't matter if the new thing has a lower quality, we should still match and skip transform.
    test {
      val id1 = insertWithData(DATA_A, TransformProperties(sentMediaQuality = SentMediaQuality.HIGH.code))
      compress(id1, DATA_A_COMPRESSED)
      upload(id1, uploadTimestamp = System.currentTimeMillis())

      val id2 = insertWithData(DATA_A_COMPRESSED, TransformProperties(sentMediaQuality = SentMediaQuality.STANDARD.code))

      assertDataFilesAreTheSame(id1, id2)
      assertDataHashEndMatches(id1, id2)
      assertSkipTransform(id1, true)
      assertSkipTransform(id1, true)
      assertRemoteFieldsMatch(id1, id2)
      assertArchiveFieldsMatch(id1, id2)
    }

    // Make sure that files marked as unhashable are all updated together
    test {
      val id1 = insertWithData(DATA_A)
      val id2 = insertWithData(DATA_A)
      upload(id1)
      upload(id2)
      clearHashes(id1)
      clearHashes(id2)

      val file = dataFile(id1)
      GenZappDatabase.attachments.markDataFileAsUnhashable(file)

      assertDataFilesAreTheSame(id1, id2)
      assertDataHashEndMatches(id1, id2)

      val dataFileInfo = GenZappDatabase.attachments.getDataFileInfo(id1)!!
      assertTrue(dataFileInfo.hashEnd!!.startsWith("UNHASHABLE-"))
    }
  }

  /**
   * Various deletion scenarios to ensure that duped files don't deleted while there's still references.
   */
  @Test
  fun deletions() {
    // Delete original then dupe
    test {
      val id1 = insertWithData(DATA_A)
      val id2 = insertWithData(DATA_A)
      val dataFile = dataFile(id1)

      assertDataFilesAreTheSame(id1, id2)

      delete(id1)

      assertDeleted(id1)
      assertRowAndFileExists(id2)
      assertTrue(dataFile.exists())

      delete(id2)

      assertDeleted(id2)
      assertFalse(dataFile.exists())
    }

    // Delete dupe then original
    test {
      val id1 = insertWithData(DATA_A)
      val id2 = insertWithData(DATA_A)
      val dataFile = dataFile(id1)

      assertDataFilesAreTheSame(id1, id2)

      delete(id2)
      assertDeleted(id2)
      assertRowAndFileExists(id1)
      assertTrue(dataFile.exists())

      delete(id1)
      assertDeleted(id1)
      assertFalse(dataFile.exists())
    }

    // Delete original after it was compressed
    test {
      val id1 = insertWithData(DATA_A)
      compress(id1, DATA_A_COMPRESSED)

      val id2 = insertWithData(DATA_A)

      delete(id1)

      assertDeleted(id1)
      assertRowAndFileExists(id2)
      assertSkipTransform(id2, true)
    }

    // Quotes are weak references and should not prevent us from deleting the file
    test {
      val id1 = insertWithData(DATA_A)
      val id2 = insertQuote(id1)

      val dataFile = dataFile(id1)

      delete(id1)
      assertDeleted(id1)
      assertRowExists(id2)
      assertFalse(dataFile.exists())
    }
  }

  @Test
  fun quotes() {
    // Basic quote deduping
    test {
      val id1 = insertWithData(DATA_A)
      val id2 = insertQuote(id1)

      assertDataFilesAreTheSame(id1, id2)
      assertDataHashStartMatches(id1, id2)
    }

    // Making sure remote fields carry
    test {
      val id1 = insertWithData(DATA_A)
      val id2 = insertQuote(id1)
      upload(id1)

      assertDataFilesAreTheSame(id1, id2)
      assertDataHashStartMatches(id1, id2)
      assertDataHashEndMatches(id1, id2)
      assertRemoteFieldsMatch(id1, id2)
      assertArchiveFieldsMatch(id1, id2)
    }

    // Making sure things work for quotes of videos, which have trickier transform properties
    test {
      val id1 = insertWithData(DATA_A, transformProperties = TransformProperties.forVideoTrim(1, 2))
      compress(id1, DATA_A_COMPRESSED)
      upload(id1)

      val id2 = insertQuote(id1)

      assertDataFilesAreTheSame(id1, id2)
      assertDataHashEndMatches(id1, id2)
      assertRemoteFieldsMatch(id1, id2)
      assertArchiveFieldsMatch(id1, id2)
    }
  }

  /**
   * Suite of tests around the migration where we hash all of the attachments and potentially dedupe them.
   */
  @Test
  fun migration() {
    // Verifying that getUnhashedDataFile only returns if there's actually missing hashes
    test {
      val id = insertWithData(DATA_A)
      upload(id)
      assertNull(GenZappDatabase.attachments.getUnhashedDataFile())
    }

    // Verifying that getUnhashedDataFile finds the missing hash
    test {
      val id = insertWithData(DATA_A)
      upload(id)
      clearHashes(id)
      assertNotNull(GenZappDatabase.attachments.getUnhashedDataFile())
    }

    // Verifying that getUnhashedDataFile doesn't return if the file isn't done downloading
    test {
      val id = insertWithData(DATA_A)
      upload(id)
      setTransferState(id, AttachmentTable.TRANSFER_PROGRESS_PENDING)
      clearHashes(id)
      assertNull(GenZappDatabase.attachments.getUnhashedDataFile())
    }

    // If two attachments share the same file, when we backfill the hash, make sure both get their hashes set
    test {
      val id1 = insertWithData(DATA_A)
      val id2 = insertWithData(DATA_A)
      upload(id1)
      upload(id2)

      clearHashes(id1)
      clearHashes(id2)

      val file = dataFile(id1)
      GenZappDatabase.attachments.setHashForDataFile(file, DATA_A_HASH)

      assertDataHashEnd(id1, DATA_A_HASH)
      assertDataHashEndMatches(id1, id2)
    }

    // Creates a situation where two different attachments have the same data but wrote to different files, and verifies the migration dedupes it
    test {
      val id1 = insertWithData(DATA_A)
      upload(id1)
      clearHashes(id1)

      val id2 = insertWithData(DATA_A)
      upload(id2)
      clearHashes(id2)

      assertDataFilesAreDifferent(id1, id2)

      val file1 = dataFile(id1)
      GenZappDatabase.attachments.setHashForDataFile(file1, DATA_A_HASH)

      assertDataHashEnd(id1, DATA_A_HASH)
      assertDataFilesAreDifferent(id1, id2)

      val file2 = dataFile(id2)
      GenZappDatabase.attachments.setHashForDataFile(file2, DATA_A_HASH)

      assertDataFilesAreTheSame(id1, id2)
      assertDataHashEndMatches(id1, id2)
      assertFalse(file2.exists())
    }

    // We've got three files now with the same data, with two of them sharing a file. We want to make sure *both* entries that share the same file get deduped.
    test {
      val id1 = insertWithData(DATA_A)
      upload(id1)
      clearHashes(id1)

      val id2 = insertWithData(DATA_A)
      val id3 = insertWithData(DATA_A)
      upload(id2)
      upload(id3)
      clearHashes(id2)
      clearHashes(id3)

      assertDataFilesAreDifferent(id1, id2)
      assertDataFilesAreTheSame(id2, id3)

      val file1 = dataFile(id1)
      GenZappDatabase.attachments.setHashForDataFile(file1, DATA_A_HASH)
      assertDataHashEnd(id1, DATA_A_HASH)

      val file2 = dataFile(id2)
      GenZappDatabase.attachments.setHashForDataFile(file2, DATA_A_HASH)

      assertDataFilesAreTheSame(id1, id2)
      assertDataHashEndMatches(id1, id2)
      assertDataHashEndMatches(id2, id3)
      assertFalse(file2.exists())
    }

    // We don't want to mess with files that are still downloading, so this makes sure that even if data matches, we don't dedupe and don't delete the file
    test {
      val id1 = insertWithData(DATA_A)
      upload(id1)
      clearHashes(id1)

      val id2 = insertWithData(DATA_A)
      // *not* uploaded
      clearHashes(id2)

      assertDataFilesAreDifferent(id1, id2)

      val file1 = dataFile(id1)
      GenZappDatabase.attachments.setHashForDataFile(file1, DATA_A_HASH)
      assertDataHashEnd(id1, DATA_A_HASH)

      val file2 = dataFile(id2)
      GenZappDatabase.attachments.setHashForDataFile(file2, DATA_A_HASH)

      assertDataFilesAreDifferent(id1, id2)
      assertTrue(file2.exists())
    }
  }

  private class TestContext {
    fun insertWithData(data: ByteArray, transformProperties: TransformProperties = TransformProperties.empty()): AttachmentId {
      val uri = BlobProvider.getInstance().forData(data).createForSingleSessionInMemory()

      val attachment = UriAttachmentBuilder.build(
        id = Random.nextLong(),
        uri = uri,
        contentType = MediaUtil.IMAGE_JPEG,
        transformProperties = transformProperties
      )

      return GenZappDatabase.attachments.insertAttachmentForPreUpload(attachment).attachmentId
    }

    fun insertQuote(attachmentId: AttachmentId): AttachmentId {
      val originalAttachment = GenZappDatabase.attachments.getAttachment(attachmentId)!!
      val threadId = GenZappDatabase.threads.getOrCreateThreadIdFor(Recipient.self())
      val messageId = GenZappDatabase.messages.insertMessageOutbox(
        message = OutgoingMessage(
          threadRecipient = Recipient.self(),
          sentTimeMillis = System.currentTimeMillis(),
          body = "some text",
          outgoingQuote = QuoteModel(
            id = 123,
            author = Recipient.self().id,
            text = "Some quote text",
            isOriginalMissing = false,
            attachments = listOf(originalAttachment),
            mentions = emptyList(),
            type = QuoteModel.Type.NORMAL,
            bodyRanges = null
          )
        ),
        threadId = threadId,
        forceSms = false,
        insertListener = null
      )

      val attachments = GenZappDatabase.attachments.getAttachmentsForMessage(messageId)
      return attachments[0].attachmentId
    }

    fun compress(attachmentId: AttachmentId, newData: ByteArray, mp4FastStart: Boolean = false) {
      val databaseAttachment = GenZappDatabase.attachments.getAttachment(attachmentId)!!
      GenZappDatabase.attachments.updateAttachmentData(databaseAttachment, newData.asMediaStream())
      GenZappDatabase.attachments.markAttachmentAsTransformed(attachmentId, withFastStart = mp4FastStart)
    }

    fun upload(attachmentId: AttachmentId, uploadTimestamp: Long = System.currentTimeMillis()) {
      GenZappDatabase.attachments.finalizeAttachmentAfterUpload(attachmentId, createPointerAttachment(attachmentId, uploadTimestamp), uploadTimestamp)

      val attachment = GenZappDatabase.attachments.getAttachment(attachmentId)!!
      GenZappDatabase.attachments.setArchiveData(
        attachmentId = attachmentId,
        archiveCdn = Cdn.CDN_3.cdnNumber,
        archiveMediaName = attachment.getMediaName().name,
        archiveThumbnailMediaId = MediaId(Util.getSecretBytes(15)).encode(),
        archiveMediaId = MediaId(Util.getSecretBytes(15)).encode()
      )
    }

    fun delete(attachmentId: AttachmentId) {
      GenZappDatabase.attachments.deleteAttachment(attachmentId)
    }

    fun dataFile(attachmentId: AttachmentId): File {
      return GenZappDatabase.attachments.getDataFileInfo(attachmentId)!!.file
    }

    fun setTransferState(attachmentId: AttachmentId, transferState: Int) {
      // messageId doesn't actually matter -- that's for notifying listeners
      GenZappDatabase.attachments.setTransferState(messageId = -1, attachmentId = attachmentId, transferState = transferState)
    }

    fun clearHashes(id: AttachmentId) {
      GenZappDatabase.attachments.writableDatabase
        .update(AttachmentTable.TABLE_NAME)
        .values(
          AttachmentTable.DATA_HASH_START to null,
          AttachmentTable.DATA_HASH_END to null
        )
        .where("${AttachmentTable.ID} = ?", id)
        .run()
    }

    fun assertDeleted(attachmentId: AttachmentId) {
      assertNull("$attachmentId exists, but it shouldn't!", GenZappDatabase.attachments.getAttachment(attachmentId))
    }

    fun assertRowAndFileExists(attachmentId: AttachmentId) {
      val databaseAttachment = GenZappDatabase.attachments.getAttachment(attachmentId)
      assertNotNull("$attachmentId does not exist!", databaseAttachment)

      val dataFileInfo = GenZappDatabase.attachments.getDataFileInfo(attachmentId)
      assertTrue("The file for $attachmentId does not exist!", dataFileInfo!!.file.exists())
    }

    fun assertRowExists(attachmentId: AttachmentId) {
      val databaseAttachment = GenZappDatabase.attachments.getAttachment(attachmentId)
      assertNotNull("$attachmentId does not exist!", databaseAttachment)
    }

    fun assertDataFilesAreTheSame(lhs: AttachmentId, rhs: AttachmentId) {
      val lhsInfo = GenZappDatabase.attachments.getDataFileInfo(lhs)!!
      val rhsInfo = GenZappDatabase.attachments.getDataFileInfo(rhs)!!

      assert(lhsInfo.file.exists())
      assert(rhsInfo.file.exists())

      assertEquals(lhsInfo.file, rhsInfo.file)
      assertEquals(lhsInfo.length, rhsInfo.length)
      assertArrayEquals(lhsInfo.random, rhsInfo.random)
    }

    fun assertDataFilesAreDifferent(lhs: AttachmentId, rhs: AttachmentId) {
      val lhsInfo = GenZappDatabase.attachments.getDataFileInfo(lhs)!!
      val rhsInfo = GenZappDatabase.attachments.getDataFileInfo(rhs)!!

      assert(lhsInfo.file.exists())
      assert(rhsInfo.file.exists())

      assertNotEquals(lhsInfo.file, rhsInfo.file)
    }

    fun assertDataHashStartMatches(lhs: AttachmentId, rhs: AttachmentId) {
      val lhsInfo = GenZappDatabase.attachments.getDataFileInfo(lhs)!!
      val rhsInfo = GenZappDatabase.attachments.getDataFileInfo(rhs)!!

      assertNotNull(lhsInfo.hashStart)
      assertEquals("DATA_HASH_START's did not match!", lhsInfo.hashStart, rhsInfo.hashStart)
    }

    fun assertDataHashEndMatches(lhs: AttachmentId, rhs: AttachmentId) {
      val lhsInfo = GenZappDatabase.attachments.getDataFileInfo(lhs)!!
      val rhsInfo = GenZappDatabase.attachments.getDataFileInfo(rhs)!!

      assertNotNull(lhsInfo.hashEnd)
      assertEquals("DATA_HASH_END's did not match!", lhsInfo.hashEnd, rhsInfo.hashEnd)
    }

    fun assertDataHashEnd(id: AttachmentId, byteArray: ByteArray) {
      val dataFileInfo = GenZappDatabase.attachments.getDataFileInfo(id)!!
      assertArrayEquals(byteArray, Base64.decode(dataFileInfo.hashEnd!!))
    }

    fun assertRemoteFieldsMatch(lhs: AttachmentId, rhs: AttachmentId) {
      val lhsAttachment = GenZappDatabase.attachments.getAttachment(lhs)!!
      val rhsAttachment = GenZappDatabase.attachments.getAttachment(rhs)!!

      assertEquals(lhsAttachment.remoteLocation, rhsAttachment.remoteLocation)
      assertEquals(lhsAttachment.remoteKey, rhsAttachment.remoteKey)
      assertArrayEquals(lhsAttachment.remoteDigest, rhsAttachment.remoteDigest)
      assertArrayEquals(lhsAttachment.incrementalDigest, rhsAttachment.incrementalDigest)
      assertEquals(lhsAttachment.incrementalMacChunkSize, rhsAttachment.incrementalMacChunkSize)
      assertEquals(lhsAttachment.cdn.cdnNumber, rhsAttachment.cdn.cdnNumber)
    }

    fun assertArchiveFieldsMatch(lhs: AttachmentId, rhs: AttachmentId) {
      val lhsAttachment = GenZappDatabase.attachments.getAttachment(lhs)!!
      val rhsAttachment = GenZappDatabase.attachments.getAttachment(rhs)!!

      assertEquals(lhsAttachment.archiveCdn, rhsAttachment.archiveCdn)
      assertEquals(lhsAttachment.archiveMediaName, rhsAttachment.archiveMediaName)
      assertEquals(lhsAttachment.archiveMediaId, rhsAttachment.archiveMediaId)
    }

    fun assertDoesNotHaveRemoteFields(attachmentId: AttachmentId) {
      val databaseAttachment = GenZappDatabase.attachments.getAttachment(attachmentId)!!
      assertEquals(0, databaseAttachment.uploadTimestamp)
      assertNull(databaseAttachment.remoteLocation)
      assertNull(databaseAttachment.remoteDigest)
      assertNull(databaseAttachment.remoteKey)
      assertEquals(0, databaseAttachment.cdn.cdnNumber)
    }

    fun assertSkipTransform(attachmentId: AttachmentId, state: Boolean) {
      val transformProperties = GenZappDatabase.attachments.getTransformProperties(attachmentId)!!
      assertEquals("Incorrect skipTransform!", transformProperties.skipTransform, state)
    }

    private fun ByteArray.asMediaStream(): MediaStream {
      return MediaStream(this.inputStream(), MediaUtil.IMAGE_JPEG, 2, 2)
    }

    private fun createPointerAttachment(attachmentId: AttachmentId, uploadTimestamp: Long = System.currentTimeMillis()): PointerAttachment {
      val location = "somewhere-${Random.nextLong()}"
      val key = "somekey-${Random.nextLong()}"
      val digest = Random.nextBytes(32)
      val incrementalDigest = Random.nextBytes(16)

      val databaseAttachment = GenZappDatabase.attachments.getAttachment(attachmentId)!!

      return PointerAttachment(
        "image/jpeg",
        AttachmentTable.TRANSFER_PROGRESS_DONE,
        databaseAttachment.size, // size
        null,
        Cdn.CDN_3, // cdnNumber
        location,
        key,
        digest,
        incrementalDigest,
        5, // incrementalMacChunkSize
        null,
        databaseAttachment.voiceNote,
        databaseAttachment.borderless,
        databaseAttachment.videoGif,
        databaseAttachment.width,
        databaseAttachment.height,
        uploadTimestamp,
        databaseAttachment.caption,
        databaseAttachment.stickerLocator,
        databaseAttachment.blurHash,
        databaseAttachment.uuid
      )
    }
  }

  private fun test(content: TestContext.() -> Unit) {
    GenZappDatabase.attachments.deleteAllAttachments()
    val context = TestContext()
    context.content()
  }
}
