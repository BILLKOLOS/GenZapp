package org.thoughtcrime.securesms.util;

import androidx.annotation.StyleRes;

import org.thoughtcrime.securesms.R;

public class DynamicConversationSettingsTheme extends DynamicTheme {

  protected @StyleRes int getTheme() {
    return R.style.GenZapp_DayNight_ConversationSettings;
  }
}
