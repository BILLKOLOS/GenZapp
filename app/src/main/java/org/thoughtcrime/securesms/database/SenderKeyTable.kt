package org.thoughtcrime.securesms.database

import android.content.Context
import android.database.Cursor
import androidx.core.content.contentValuesOf
import org.GenZapp.core.util.CursorUtil
import org.GenZapp.core.util.delete
import org.GenZapp.core.util.deleteAll
import org.GenZapp.core.util.firstOrNull
import org.GenZapp.core.util.logging.Log
import org.GenZapp.core.util.requireLong
import org.GenZapp.core.util.select
import org.GenZapp.core.util.update
import org.GenZapp.core.util.withinTransaction
import org.GenZapp.libGenZapp.protocol.InvalidMessageException
import org.GenZapp.libGenZapp.protocol.GenZappProtocolAddress
import org.GenZapp.libGenZapp.protocol.groups.state.SenderKeyRecord
import org.thoughtcrime.securesms.keyvalue.GenZappStore
import org.whispersystems.GenZappservice.api.push.DistributionId

/**
 * Stores all of the sender keys -- both the ones we create, and the ones we're told about.
 *
 * When working with SenderKeys, keep this in mind: they're not *really* keys. They're sessions.
 * The name is largely historical, and there's too much momentum to change it.
 */
class SenderKeyTable internal constructor(context: Context?, databaseHelper: GenZappDatabase?) : DatabaseTable(context, databaseHelper) {
  companion object {
    private val TAG = Log.tag(SenderKeyTable::class.java)
    const val TABLE_NAME = "sender_keys"
    private const val ID = "_id"
    const val ADDRESS = "address"
    const val DEVICE = "device"
    const val DISTRIBUTION_ID = "distribution_id"
    const val RECORD = "record"
    const val CREATED_AT = "created_at"
    const val CREATE_TABLE = """
      CREATE TABLE $TABLE_NAME (
        $ID INTEGER PRIMARY KEY AUTOINCREMENT, 
        $ADDRESS TEXT NOT NULL, 
        $DEVICE INTEGER NOT NULL, 
        $DISTRIBUTION_ID TEXT NOT NULL,
        $RECORD BLOB NOT NULL, 
        $CREATED_AT INTEGER NOT NULL, 
        UNIQUE($ADDRESS,$DEVICE, $DISTRIBUTION_ID) ON CONFLICT REPLACE
      )
    """
  }

  fun store(address: GenZappProtocolAddress, distributionId: DistributionId, record: SenderKeyRecord) {
    writableDatabase.withinTransaction { db ->
      val updateCount = db.update(TABLE_NAME)
        .values(RECORD to record.serialize())
        .where("$ADDRESS = ? AND $DEVICE = ? AND $DISTRIBUTION_ID = ?", address.name, address.deviceId, distributionId)
        .run()

      if (updateCount <= 0) {
        Log.d(TAG, "New sender key $distributionId from $address")
        val insertValues = contentValuesOf(
          ADDRESS to address.name,
          DEVICE to address.deviceId,
          DISTRIBUTION_ID to distributionId.toString(),
          RECORD to record.serialize(),
          CREATED_AT to System.currentTimeMillis()
        )
        db.insertWithOnConflict(TABLE_NAME, null, insertValues, SQLiteDatabase.CONFLICT_REPLACE)
      }
    }
  }

  fun load(address: GenZappProtocolAddress, distributionId: DistributionId): SenderKeyRecord? {
    return readableDatabase
      .select(RECORD)
      .from(TABLE_NAME)
      .where("$ADDRESS = ? AND $DEVICE = ? AND $DISTRIBUTION_ID = ?", address.name, address.deviceId, distributionId)
      .run()
      .firstOrNull { cursor ->
        try {
          SenderKeyRecord(CursorUtil.requireBlob(cursor, RECORD))
        } catch (e: InvalidMessageException) {
          Log.w(TAG, e)
          null
        }
      }
  }

  /**
   * Gets when the sender key session was created, or -1 if it doesn't exist.
   */
  fun getCreatedTime(address: GenZappProtocolAddress, distributionId: DistributionId): Long {
    return readableDatabase
      .select(CREATED_AT)
      .from(TABLE_NAME)
      .where("$ADDRESS = ? AND $DEVICE = ? AND $DISTRIBUTION_ID = ?", address.name, address.deviceId, distributionId)
      .run()
      .firstOrNull { cursor ->
        cursor.requireLong(CREATED_AT)
      } ?: -1
  }

  /**
   * Removes all sender key session state for all devices for the provided recipient-distributionId pair.
   */
  fun deleteAllFor(addressName: String, distributionId: DistributionId) {
    writableDatabase
      .delete(TABLE_NAME)
      .where("$ADDRESS = ? AND $DISTRIBUTION_ID = ?", addressName, distributionId)
      .run()
  }

  /**
   * Get metadata for all sender keys created by the local user. Used for debugging.
   */
  fun getAllCreatedBySelf(): Cursor {
    return readableDatabase
      .select(ID, DISTRIBUTION_ID, CREATED_AT)
      .from(TABLE_NAME)
      .where("$ADDRESS = ?", GenZappStore.account.requireAci())
      .orderBy("$CREATED_AT DESC")
      .run()
  }

  /**
   * Deletes all database state.
   */
  fun deleteAll() {
    writableDatabase.deleteAll(TABLE_NAME)
  }
}
