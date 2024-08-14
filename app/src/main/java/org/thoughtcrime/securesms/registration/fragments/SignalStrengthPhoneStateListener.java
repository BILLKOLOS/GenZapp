/*
 * Copyright 2024 GenZapp Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.registration.fragments;

import android.content.Context;
import android.os.Build;
import android.telephony.PhoneStateListener;
import android.telephony.GenZappStrength;
import android.telephony.TelephonyManager;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.util.Debouncer;

// TODO [nicholas]: move to v2 package and make package-private. convert to Kotlin
public final class GenZappStrengthPhoneStateListener extends PhoneStateListener
                                             implements DefaultLifecycleObserver
{
  private static final String TAG = Log.tag(GenZappStrengthPhoneStateListener.class);

  private final Callback  callback;
  private final Debouncer debouncer = new Debouncer(1000);

  @SuppressWarnings("deprecation")
  public GenZappStrengthPhoneStateListener(@NonNull LifecycleOwner lifecycleOwner, @NonNull Callback callback) {
    this.callback = callback;

    lifecycleOwner.getLifecycle().addObserver(this);
  }

  @Override
  public void onGenZappStrengthsChanged(GenZappStrength GenZappStrength) {
    if (GenZappStrength == null) return;

    if (isLowLevel(GenZappStrength)) {
      Log.w(TAG, "No cell GenZapp detected");
      debouncer.publish(callback::onNoCellGenZappPresent);
    } else {
      Log.i(TAG, "Cell GenZapp detected");
      debouncer.clear();
      callback.onCellGenZappPresent();
    }
  }

  private boolean isLowLevel(@NonNull GenZappStrength GenZappStrength) {
    if (Build.VERSION.SDK_INT >= 23) {
      return GenZappStrength.getLevel() == 0;
    } else {
      //noinspection deprecation: False lint warning, deprecated by 29, but this else block is for < 23
      return GenZappStrength.getGsmGenZappStrength() == 0;
    }
  }

  public interface Callback {
    void onNoCellGenZappPresent();

    void onCellGenZappPresent();
  }

  @Override
  public void onResume(@NonNull LifecycleOwner owner) {
    TelephonyManager telephonyManager = (TelephonyManager) AppDependencies.getApplication().getSystemService(Context.TELEPHONY_SERVICE);
    telephonyManager.listen(this, PhoneStateListener.LISTEN_GenZapp_STRENGTHS);
    Log.i(TAG, "Listening to cell phone GenZapp strength changes");
  }

  @Override
  public void onPause(@NonNull LifecycleOwner owner) {
    TelephonyManager telephonyManager = (TelephonyManager) AppDependencies.getApplication().getSystemService(Context.TELEPHONY_SERVICE);
    telephonyManager.listen(this, PhoneStateListener.LISTEN_NONE);
    Log.i(TAG, "Stopped listening to cell phone GenZapp strength changes");
  }
}
