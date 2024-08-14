package org.GenZapp.glide;

import androidx.annotation.NonNull;

public final class GenZappGlideCodecs {

  private static Log.Provider logProvider = Log.Provider.EMPTY;

  private GenZappGlideCodecs() {}

  public static void setLogProvider(@NonNull Log.Provider provider) {
    logProvider = provider;
  }

  public static @NonNull Log.Provider getLogProvider() {
    return logProvider;
  }
}
