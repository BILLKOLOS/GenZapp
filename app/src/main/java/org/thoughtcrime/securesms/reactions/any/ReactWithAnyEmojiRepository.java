package org.thoughtcrime.securesms.reactions.any;

import android.content.Context;

import androidx.annotation.NonNull;

import com.annimon.stream.Stream;

import org.GenZapp.core.util.ThreadUtil;
import org.GenZapp.core.util.concurrent.GenZappExecutors;
import org.GenZapp.core.util.logging.Log;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.emoji.RecentEmojiPageModel;
import org.thoughtcrime.securesms.database.GenZappDatabase;
import org.thoughtcrime.securesms.database.model.MessageId;
import org.thoughtcrime.securesms.database.model.ReactionRecord;
import org.thoughtcrime.securesms.emoji.EmojiCategory;
import org.thoughtcrime.securesms.emoji.EmojiSource;
import org.thoughtcrime.securesms.reactions.ReactionDetails;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.sms.MessageSender;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

final class ReactWithAnyEmojiRepository {

  private static final String TAG = Log.tag(ReactWithAnyEmojiRepository.class);

  private final Context                     context;
  private final RecentEmojiPageModel        recentEmojiPageModel;
  private final List<ReactWithAnyEmojiPage> emojiPages;

  ReactWithAnyEmojiRepository(@NonNull Context context, @NonNull String storageKey) {
    this.context              = context;
    this.recentEmojiPageModel = new RecentEmojiPageModel(context, storageKey);
    this.emojiPages           = new LinkedList<>();

    emojiPages.addAll(Stream.of(EmojiSource.getLatest().getDisplayPages())
                            .filterNot(p -> p.getIconAttr() == EmojiCategory.EMOTICONS.getIcon())
                            .map(page -> new ReactWithAnyEmojiPage(Collections.singletonList(new ReactWithAnyEmojiPageBlock(EmojiCategory.getCategoryLabel(page.getIconAttr()), page))))
                            .toList());
  }

  List<ReactWithAnyEmojiPage> getEmojiPageModels(@NonNull List<ReactionDetails> thisMessagesReactions) {
    List<ReactWithAnyEmojiPage> pages       = new LinkedList<>();
    List<String>                thisMessage = Stream.of(thisMessagesReactions)
                                                    .map(ReactionDetails::getDisplayEmoji)
                                                    .distinct()
                                                    .toList();

    if (thisMessage.isEmpty()) {
      pages.add(new ReactWithAnyEmojiPage(Collections.singletonList(new ReactWithAnyEmojiPageBlock(R.string.ReactWithAnyEmojiBottomSheetDialogFragment__recently_used, recentEmojiPageModel))));
    } else {
      pages.add(new ReactWithAnyEmojiPage(Arrays.asList(new ReactWithAnyEmojiPageBlock(R.string.ReactWithAnyEmojiBottomSheetDialogFragment__this_message, new ThisMessageEmojiPageModel(thisMessage)),
                                                        new ReactWithAnyEmojiPageBlock(R.string.ReactWithAnyEmojiBottomSheetDialogFragment__recently_used, recentEmojiPageModel))));
    }

    pages.addAll(emojiPages);

    return pages;
  }

  void addEmojiToMessage(@NonNull String emoji, @NonNull MessageId messageId) {
    GenZappExecutors.BOUNDED.execute(() -> {
      ReactionRecord  oldRecord = Stream.of(GenZappDatabase.reactions().getReactions(messageId))
                                        .filter(record -> record.getAuthor().equals(Recipient.self().getId()))
                                        .findFirst()
                                        .orElse(null);

      if (oldRecord != null && oldRecord.getEmoji().equals(emoji)) {
        MessageSender.sendReactionRemoval(context, messageId, oldRecord);
      } else {
        MessageSender.sendNewReaction(context, messageId, emoji);
        ThreadUtil.runOnMain(() -> recentEmojiPageModel.onCodePointSelected(emoji));
      }
    });
  }
}
