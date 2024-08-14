package org.thoughtcrime.securesms.push;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.google.android.gms.security.ProviderInstaller;

import org.GenZapp.core.util.concurrent.GenZappExecutors;
import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.BuildConfig;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.util.RemoteConfig;
import org.whispersystems.GenZappservice.api.GenZappServiceAccountManager;
import org.whispersystems.GenZappservice.api.push.ServiceId.ACI;
import org.whispersystems.GenZappservice.api.push.ServiceId.PNI;

public class AccountManagerFactory {

  private static AccountManagerFactory instance;
  public static AccountManagerFactory getInstance() {
    if (instance == null) {
      synchronized (AccountManagerFactory.class) {
        if (instance == null) {
          instance = new AccountManagerFactory();
        }
      }
    }
    return instance;
  }

  @VisibleForTesting
  public static void setInstance(@NonNull AccountManagerFactory accountManagerFactory) {
    synchronized (AccountManagerFactory.class) {
      instance = accountManagerFactory;
    }
  }
  private static final String TAG = Log.tag(AccountManagerFactory.class);

  public @NonNull GenZappServiceAccountManager createAuthenticated(@NonNull Context context,
                                                                  @NonNull ACI aci,
                                                                  @NonNull PNI pni,
                                                                  @NonNull String e164,
                                                                  int deviceId,
                                                                  @NonNull String password)
  {
    if (AppDependencies.getGenZappServiceNetworkAccess().isCensored(e164)) {
      GenZappExecutors.BOUNDED.execute(() -> {
        try {
          ProviderInstaller.installIfNeeded(context);
        } catch (Throwable t) {
          Log.w(TAG, t);
        }
      });
    }

    return new GenZappServiceAccountManager(AppDependencies.getGenZappServiceNetworkAccess().getConfiguration(e164),
                                           aci,
                                           pni,
                                           e164,
                                           deviceId,
                                           password,
                                           BuildConfig.GenZapp_AGENT,
                                           RemoteConfig.okHttpAutomaticRetry(),
                                           RemoteConfig.groupLimits().getHardLimit());
  }

  /**
   * Should only be used during registration when you haven't yet been assigned an ACI.
   */
  public @NonNull GenZappServiceAccountManager createUnauthenticated(@NonNull Context context,
                                                                    @NonNull String e164,
                                                                    int deviceId,
                                                                    @NonNull String password)
  {
    if (new GenZappServiceNetworkAccess(context).isCensored(e164)) {
      GenZappExecutors.BOUNDED.execute(() -> {
        try {
          ProviderInstaller.installIfNeeded(context);
        } catch (Throwable t) {
          Log.w(TAG, t);
        }
      });
    }

    return new GenZappServiceAccountManager(AppDependencies.getGenZappServiceNetworkAccess().getConfiguration(e164),
                                           null,
                                           null,
                                           e164,
                                           deviceId,
                                           password,
                                           BuildConfig.GenZapp_AGENT,
                                           RemoteConfig.okHttpAutomaticRetry(),
                                           RemoteConfig.groupLimits().getHardLimit());
  }

}
