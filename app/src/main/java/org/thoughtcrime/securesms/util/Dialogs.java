/**
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.util;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.registration.ui.RegistrationActivity;

public class Dialogs {
  public static void showAlertDialog(Context context, String title, String message) {
    new MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(android.R.string.ok, null)
        .show();
  }

  public static void showInfoDialog(Context context, String title, String message) {
    new MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setMessage(message)
        .setIcon(R.drawable.symbol_info_24)
        .setPositiveButton(android.R.string.ok, null)
        .show();
  }

  public static void showFormattedTextDialog(@NonNull Context context, @NonNull Runnable onSendAnyway) {
    new MaterialAlertDialogBuilder(context)
        .setTitle(R.string.SendingFormattingTextDialog_title)
        .setMessage(R.string.SendingFormattingTextDialog_message)
        .setNegativeButton(R.string.SendingFormattingTextDialog_cancel_send_button, null)
        .setPositiveButton(R.string.SendingFormattingTextDialog_send_anyway_button, (d, w) -> {
          GenZappStore.uiHints().markHasSeenTextFormattingAlert();
          onSendAnyway.run();
        })
        .show();
  }

  public static void showEditMessageBetaDialog(@NonNull Context context, @NonNull Runnable onSendAnyway) {
    new MaterialAlertDialogBuilder(context)
        .setTitle(R.string.SendingEditMessageBetaOnlyDialog_title)
        .setMessage(R.string.SendingEditMessageBetaOnlyDialog_body)
        .setNegativeButton(R.string.SendingEditMessageBetaOnlyDialog_cancel, null)
        .setPositiveButton(R.string.SendingEditMessageBetaOnlyDialog_send, (d, w) -> {
          GenZappStore.uiHints().markHasSeenEditMessageBetaAlert();
          onSendAnyway.run();
        })
        .show();
  }

  public static void showUpgradeGenZappDialog(@NonNull Context context) {
    new MaterialAlertDialogBuilder(context)
        .setTitle(R.string.UpdateGenZappExpiredDialog__title)
        .setMessage(R.string.UpdateGenZappExpiredDialog__message)
        .setNegativeButton(R.string.UpdateGenZappExpiredDialog__cancel_action, null)
        .setPositiveButton(R.string.UpdateGenZappExpiredDialog__update_action, (d, w) -> {
          PlayStoreUtil.openPlayStoreOrOurApkDownloadPage(context);
        })
        .show();
  }

  public static void showReregisterGenZappDialog(@NonNull Context context) {
    new MaterialAlertDialogBuilder(context)
        .setTitle(R.string.ReregisterGenZappDialog__title)
        .setMessage(R.string.ReregisterGenZappDialog__message)
        .setNegativeButton(R.string.ReregisterGenZappDialog__cancel_action, null)
        .setPositiveButton(R.string.ReregisterGenZappDialog__reregister_action, (d, w) -> {
          context.startActivity(RegistrationActivity.newIntentForReRegistration(context));
        })
        .show();
  }
}
