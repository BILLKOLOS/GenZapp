package org.thoughtcrime.securesms.wallpaper;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;

import org.GenZapp.core.util.concurrent.GenZappExecutors;
import org.thoughtcrime.securesms.conversation.colors.ChatColors;
import org.thoughtcrime.securesms.conversation.colors.ChatColorsPalette;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.concurrent.SerialExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

class ChatWallpaperRepository {

  private static final Executor EXECUTOR = new SerialExecutor(GenZappExecutors.BOUNDED);

  @MainThread
  @Nullable ChatWallpaper getCurrentWallpaper(@Nullable RecipientId recipientId) {
    if (recipientId != null) {
      return Recipient.live(recipientId).get().getWallpaper();
    } else {
      return GenZappStore.wallpaper().getWallpaper();
    }
  }

  @MainThread
  @NonNull ChatColors getCurrentChatColors(@Nullable RecipientId recipientId) {
    if (recipientId != null) {
      return Recipient.live(recipientId).get().getChatColors();
    } else if (GenZappStore.chatColors().hasChatColors()) {
      return Objects.requireNonNull(GenZappStore.chatColors().getChatColors());
    } else if (GenZappStore.wallpaper().hasWallpaperSet()) {
      return Objects.requireNonNull(GenZappStore.wallpaper().getWallpaper()).getAutoChatColors();
    } else {
      return ChatColorsPalette.Bubbles.getDefault().withId(ChatColors.Id.Auto.INSTANCE);
    }
  }

  void getAllWallpaper(@NonNull Consumer<List<ChatWallpaper>> consumer) {
    EXECUTOR.execute(() -> {
      List<ChatWallpaper> wallpapers = new ArrayList<>(ChatWallpaper.BuiltIns.INSTANCE.getAllBuiltIns());

      wallpapers.addAll(WallpaperStorage.getAll(AppDependencies.getApplication()));
      consumer.accept(wallpapers);
    });
  }

  void saveWallpaper(@Nullable RecipientId recipientId, @Nullable ChatWallpaper chatWallpaper, @NonNull Runnable onWallpaperSaved) {
    if (recipientId != null) {
      //noinspection CodeBlock2Expr
      EXECUTOR.execute(() -> {
        GenZappDatabase.recipients().setWallpaper(recipientId, chatWallpaper);
        onWallpaperSaved.run();
      });
    } else {
      GenZappStore.wallpaper().setWallpaper(AppDependencies.getApplication(), chatWallpaper);
      onWallpaperSaved.run();
    }
  }

  void resetAllWallpaper(@NonNull Runnable onWallpaperReset) {
    GenZappStore.wallpaper().setWallpaper(AppDependencies.getApplication(), null);
    EXECUTOR.execute(() -> {
      GenZappDatabase.recipients().resetAllWallpaper();
      onWallpaperReset.run();
    });
  }

  void resetAllChatColors(@NonNull Runnable onColorsReset) {
    GenZappStore.chatColors().setChatColors(null);
    EXECUTOR.execute(() -> {
      GenZappDatabase.recipients().clearAllColors();
      onColorsReset.run();
    });
  }

  void setDimInDarkTheme(@Nullable RecipientId recipientId, boolean dimInDarkTheme) {
    if (recipientId != null) {
      EXECUTOR.execute(() -> {
        Recipient recipient = Recipient.resolved(recipientId);
        if (recipient.getHasOwnWallpaper()) {
          GenZappDatabase.recipients().setDimWallpaperInDarkTheme(recipientId, dimInDarkTheme);
        } else if (recipient.getHasWallpaper()) {
          GenZappDatabase.recipients()
                       .setWallpaper(recipientId,
                                     ChatWallpaperFactory.updateWithDimming(recipient.getWallpaper(),
                                                                            dimInDarkTheme ? ChatWallpaper.FIXED_DIM_LEVEL_FOR_DARK_THEME
                                                                                           : 0f));
        } else {
          throw new IllegalStateException("Unexpected call to setDimInDarkTheme, no wallpaper has been set on the given recipient or globally.");
        }
      });
    } else {
      GenZappStore.wallpaper().setDimInDarkTheme(dimInDarkTheme);
    }
  }

  public void clearChatColor(@Nullable RecipientId recipientId, @NonNull Runnable onChatColorCleared) {
    if (recipientId == null) {
      GenZappStore.chatColors().setChatColors(null);
      onChatColorCleared.run();
    } else {
      EXECUTOR.execute(() -> {
        GenZappDatabase.recipients().clearColor(recipientId);
        onChatColorCleared.run();
      });
    }
  }
}
