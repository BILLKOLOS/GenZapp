package org.thoughtcrime.securesms.messagedetails;

import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;

import org.GenZapp.core.util.concurrent.GenZappExecutors;
import org.thoughtcrime.securesms.database.DatabaseObserver;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.database.model.MessageId;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.dependencies.AppDependencies;

final class MessageRecordLiveData extends LiveData<MessageRecord> {

  private final DatabaseObserver.Observer observer;
  private final MessageId                 messageId;

  MessageRecordLiveData(MessageId messageId) {
    this.messageId = messageId;
    this.observer  = this::retrieveMessageRecordActual;
  }

  @Override
  protected void onActive() {
    GenZappExecutors.BOUNDED_IO.execute(this::retrieveMessageRecordActual);
  }

  @Override
  protected void onInactive() {
    AppDependencies.getDatabaseObserver().unregisterObserver(observer);
  }

  @WorkerThread
  private synchronized void retrieveMessageRecordActual() {
    try {
      MessageRecord record = GenZappDatabase.messages().getMessageRecord(messageId.getId());

      if (record.isPaymentNotification()) {
        record = GenZappDatabase.payments().updateMessageWithPayment(record);
      }

      postValue(record);
      AppDependencies.getDatabaseObserver().registerVerboseConversationObserver(record.getThreadId(), observer);
    } catch (NoSuchMessageException ignored) {
      postValue(null);
    }
  }
}
