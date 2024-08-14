package org.thoughtcrime.securesms.push;

import android.content.Context;

import org.thoughtcrime.securesms.R;
import org.whispersystems.GenZappservice.api.push.TrustStore;

import java.io.InputStream;

public class GenZappServiceTrustStore implements TrustStore {

  private final Context context;

  public GenZappServiceTrustStore(Context context) {
    this.context = context.getApplicationContext();
  }

  @Override
  public InputStream getKeyStoreInputStream() {
    return context.getResources().openRawResource(R.raw.whisper);
  }

  @Override
  public String getKeyStorePassword() {
    return "whisper";
  }
}
