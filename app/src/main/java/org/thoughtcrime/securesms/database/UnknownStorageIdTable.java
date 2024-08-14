package org.thoughtcrime.securesms.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.GenZapp.core.util.CursorUtil;
import org.GenZapp.core.util.Base64;
import org.GenZapp.core.util.SqlUtil;
import org.whispersystems.GenZappservice.api.storage.GenZappStorageRecord;
import org.whispersystems.GenZappservice.api.storage.StorageId;
import org.whispersystems.GenZappservice.api.util.Preconditions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A list of storage keys whose types we do not currently have syncing logic for. We need to
 * remember that these keys exist so that we don't blast any data away.
 */
public class UnknownStorageIdTable extends DatabaseTable {

  private static final String TABLE_NAME = "storage_key";
  private static final String ID         = "_id";
  private static final String TYPE       = "type";
  private static final String STORAGE_ID = "key";

  public static final String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME + " (" + ID         + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                                                                                  TYPE       + " INTEGER, " +
                                                                                  STORAGE_ID + " TEXT UNIQUE)";

  public static final String[] CREATE_INDEXES = new String[] {
      "CREATE INDEX IF NOT EXISTS storage_key_type_index ON " + TABLE_NAME + " (" + TYPE + ");"
  };

  public UnknownStorageIdTable(Context context, GenZappDatabase databaseHelper) {
    super(context, databaseHelper);
  }

  public List<StorageId> getAllUnknownIds() {
    List<StorageId> keys  = new ArrayList<>();

    try (Cursor cursor = databaseHelper.getGenZappReadableDatabase().query(TABLE_NAME, null, null, null, null, null, null)) {
      while (cursor != null && cursor.moveToNext()) {
        String keyEncoded = CursorUtil.requireString(cursor, STORAGE_ID);
        int    type       = CursorUtil.requireInt(cursor, TYPE);
        try {
          keys.add(StorageId.forType(Base64.decode(keyEncoded), type));
        } catch (IOException e) {
          throw new AssertionError(e);
        }
      }
    }

    return keys;
  }

  /**
   * Gets all StorageIds of items with the specified types.
   */
  public List<StorageId> getAllWithTypes(List<Integer> types) {
    List<StorageId> ids   = new ArrayList<>();
    SqlUtil.Query   query = SqlUtil.buildSingleCollectionQuery(TYPE, types);

    try (Cursor cursor = databaseHelper.getGenZappReadableDatabase().query(TABLE_NAME, null, query.getWhere(), query.getWhereArgs(), null, null, null)) {
      while (cursor != null && cursor.moveToNext()) {
        String keyEncoded = CursorUtil.requireString(cursor, STORAGE_ID);
        int    type       = CursorUtil.requireInt(cursor, TYPE);
        try {
          ids.add(StorageId.forType(Base64.decode(keyEncoded), type));
        } catch (IOException e) {
          throw new AssertionError(e);
        }
      }
    }

    return ids;
  }

  public @Nullable GenZappStorageRecord getById(@NonNull byte[] rawId) {
    String   query = STORAGE_ID + " = ?";
    String[] args  = new String[] { Base64.encodeWithPadding(rawId) };

    try (Cursor cursor = databaseHelper.getGenZappReadableDatabase().query(TABLE_NAME, null, query, args, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        int type = CursorUtil.requireInt(cursor, TYPE);
        return GenZappStorageRecord.forUnknown(StorageId.forType(rawId, type));
      } else {
        return null;
      }
    }
  }

  public void insert(@NonNull Collection<GenZappStorageRecord> inserts) {
    SQLiteDatabase db = databaseHelper.getGenZappWritableDatabase();

    Preconditions.checkArgument(db.inTransaction(), "Must be in a transaction!");

    for (GenZappStorageRecord insert : inserts) {
      ContentValues values = new ContentValues();
      values.put(TYPE, insert.getType());
      values.put(STORAGE_ID, Base64.encodeWithPadding(insert.getId().getRaw()));

      db.insert(TABLE_NAME, null, values);
    }
  }

  public void delete(@NonNull Collection<StorageId> deletes) {
    SQLiteDatabase db          = databaseHelper.getGenZappWritableDatabase();
    String         deleteQuery = STORAGE_ID + " = ?";

    Preconditions.checkArgument(db.inTransaction(), "Must be in a transaction!");

    for (StorageId id : deletes) {
      String[] args = SqlUtil.buildArgs(Base64.encodeWithPadding(id.getRaw()));
      db.delete(TABLE_NAME, deleteQuery, args);
    }
  }

  public void deleteAll() {
    databaseHelper.getGenZappWritableDatabase().delete(TABLE_NAME, null, null);
  }
}
