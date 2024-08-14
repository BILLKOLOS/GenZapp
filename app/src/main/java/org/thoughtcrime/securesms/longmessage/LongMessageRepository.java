package org.thoughtcrime.securesms.longmessage;

import android.content.Context;
import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import org.GenZapp.core.util.concurrent.GenZappExecutors;
import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.conversation.ConversationMessage;
import org.thoughtcrime.securesms.database.MessageTable;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.database.model.MmsMessageRecord;

import java.util.Optional;

class LongMessageRepository {

  private final static String TAG = Log.tag(LongMessageRepository.class);

  private final MessageTable messageTable;

  LongMessageRepository() {
    this.messageTable = GenZappDatabase.messages();
  }

  void getMessage(@NonNull Context context, long messageId, @NonNull Callback<Optional<LongMessage>> callback) {
    GenZappExecutors.BOUNDED.execute(() -> {
      callback.onComplete(getMmsLongMessage(context, messageTable, messageId));
    });
  }

  @WorkerThread
  private Optional<LongMessage> getMmsLongMessage(@NonNull Context context, @NonNull MessageTable mmsDatabase, long messageId) {
    Optional<MmsMessageRecord> record = getMmsMessage(mmsDatabase, messageId);
    if (record.isPresent()) {
      final ConversationMessage resolvedMessage = LongMessageResolveerKt.resolveBody(record.get(), context);
      return  Optional.of(new LongMessage(resolvedMessage));
    } else {
      return Optional.empty();
    }
  }

  @WorkerThread
  private Optional<MmsMessageRecord> getMmsMessage(@NonNull MessageTable mmsDatabase, long messageId) {
    try (Cursor cursor = mmsDatabase.getMessageCursor(messageId)) {
      return Optional.ofNullable((MmsMessageRecord) MessageTable.mmsReaderFor(cursor).getNext());
    }
  }


  interface Callback<T> {
    void onComplete(T result);
  }
}
