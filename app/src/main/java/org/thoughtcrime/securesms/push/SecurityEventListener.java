package org.thoughtcrime.securesms.push;

import android.content.Context;

import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.crypto.SecurityEvent;
import org.whispersystems.GenZappservice.api.GenZappServiceMessageSender;
import org.whispersystems.GenZappservice.api.push.GenZappServiceAddress;

public class SecurityEventListener implements GenZappServiceMessageSender.EventListener {

  private static final String TAG = Log.tag(SecurityEventListener.class);

  private final Context context;

  public SecurityEventListener(Context context) {
    this.context = context.getApplicationContext();
  }

  @Override
  public void onSecurityEvent(GenZappServiceAddress textSecureAddress) {
    SecurityEvent.broadcastSecurityUpdateEvent(context);
  }
}
