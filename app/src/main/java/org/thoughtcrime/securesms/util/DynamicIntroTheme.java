package org.thoughtcrime.securesms.util;

import androidx.annotation.StyleRes;

import org.thoughtcrime.securesms.R;

public class DynamicIntroTheme extends DynamicTheme {

  protected @StyleRes int getTheme() {
    return R.style.GenZapp_DayNight_IntroTheme;
  }
}
