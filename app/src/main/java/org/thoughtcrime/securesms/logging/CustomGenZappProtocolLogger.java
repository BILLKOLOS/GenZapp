package org.thoughtcrime.securesms.logging;

import org.GenZapp.core.util.logging.Log;
import org.GenZapp.libGenZapp.protocol.logging.GenZappProtocolLogger;

public class CustomGenZappProtocolLogger implements GenZappProtocolLogger {
  @Override
  public void log(int priority, String tag, String message) {
    switch (priority) {
      case VERBOSE:
        Log.v(tag, message);
        break;
      case DEBUG:
        Log.d(tag, message);
        break;
      case INFO:
        Log.i(tag, message);
        break;
      case WARN:
        Log.w(tag, message);
        break;
      case ERROR:
      case ASSERT:
        Log.e(tag, message, true);
        break;
    }
  }
}
