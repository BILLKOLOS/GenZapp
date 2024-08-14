package org.thoughtcrime.securesms.util;

import android.database.sqlite.SQLiteDatabaseCorruptException;

import androidx.annotation.NonNull;

import org.GenZapp.core.util.ExceptionUtil;
import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.database.LogDatabase;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;

import java.io.IOException;

import javax.net.ssl.SSLException;

import io.reactivex.rxjava3.exceptions.OnErrorNotImplementedException;

public class GenZappUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {

  private static final String TAG = Log.tag(GenZappUncaughtExceptionHandler.class);

  private final Thread.UncaughtExceptionHandler originalHandler;

  public GenZappUncaughtExceptionHandler(@NonNull Thread.UncaughtExceptionHandler originalHandler) {
    this.originalHandler = originalHandler;
  }

  @Override
  public void uncaughtException(@NonNull Thread t, @NonNull Throwable e) {
    // Seeing weird situations where SSLExceptions aren't being caught as IOExceptions
    if (e instanceof SSLException) {
      if (e instanceof IOException) {
        Log.w(TAG, "Uncaught SSLException! It *is* an IOException!", e);
      } else {
        Log.w(TAG, "Uncaught SSLException! It is *not* an IOException!", e);
      }
      return;
    }

    if (e instanceof SQLiteDatabaseCorruptException) {
      if (e.getMessage().indexOf("message_fts") >= 0) {
        Log.w(TAG, "FTS corrupted! Resetting FTS index.");
        GenZappDatabase.messageSearch().fullyResetTables();
      } else {
        Log.w(TAG, "Some non-FTS related corruption?");
      }
    }

    if (e instanceof OnErrorNotImplementedException && e.getCause() != null) {
      e = e.getCause();
    }

    String exceptionName = e.getClass().getCanonicalName();
    if (exceptionName == null) {
      exceptionName = e.getClass().getName();
    }

    Log.e(TAG, "", e, true);
    LogDatabase.getInstance(AppDependencies.getApplication()).crashes().saveCrash(System.currentTimeMillis(), exceptionName, e.getMessage(), ExceptionUtil.convertThrowableToString(e));
    GenZappStore.blockUntilAllWritesFinished();
    Log.blockUntilAllWritesFinished();
    AppDependencies.getJobManager().flush();
    originalHandler.uncaughtException(t, ExceptionUtil.joinStackTraceAndMessage(e));
  }
}
