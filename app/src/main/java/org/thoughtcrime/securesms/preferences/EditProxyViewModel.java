package org.thoughtcrime.securesms.preferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;

import org.GenZapp.core.util.concurrent.GenZappExecutors;
import org.thoughtcrime.securesms.dependencies.AppDependencies;
import org.thoughtcrime.securesms.keyvalue.GenZappStore;
import org.thoughtcrime.securesms.util.GenZappProxyUtil;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.GenZappservice.api.websocket.WebSocketConnectionState;
import org.whispersystems.GenZappservice.internal.configuration.GenZappProxy;

import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.subjects.BehaviorSubject;
import io.reactivex.rxjava3.subjects.PublishSubject;

public class EditProxyViewModel extends ViewModel {

  private final PublishSubject<Event>              events;
  private final BehaviorSubject<UiState>           uiState;
  private final BehaviorSubject<SaveState>         saveState;
  private final Flowable<WebSocketConnectionState> pipeState;

  public EditProxyViewModel() {
    this.events    = PublishSubject.create();
    this.uiState   = BehaviorSubject.create();
    this.saveState = BehaviorSubject.createDefault(SaveState.IDLE);
    this.pipeState = GenZappStore.account().getE164() == null ? Flowable.empty()
                                                             : AppDependencies.getWebSocketObserver()
                                                                              .toFlowable(BackpressureStrategy.LATEST);

    if (GenZappStore.proxy().isProxyEnabled()) {
      uiState.onNext(UiState.ALL_ENABLED);
    } else {
      uiState.onNext(UiState.ALL_DISABLED);
    }
  }

  void onToggleProxy(boolean enabled, String text) {
    if (enabled) {
      GenZappProxy currentProxy = GenZappStore.proxy().getProxy();

      if (currentProxy != null && !Util.isEmpty(currentProxy.getHost())) {
        GenZappProxyUtil.enableProxy(currentProxy);
      }
      uiState.onNext(UiState.ALL_ENABLED);
    } else if (Util.isEmpty(text)) {
        GenZappProxyUtil.disableAndClearProxy();
        uiState.onNext(UiState.ALL_DISABLED);
    } else {
        GenZappProxyUtil.disableProxy();
        uiState.onNext(UiState.ALL_DISABLED);
    }
  }

  public void onSaveClicked(@NonNull String host) {
    String trueHost = GenZappProxyUtil.convertUserEnteredAddressToHost(host);

    saveState.onNext(SaveState.IN_PROGRESS);

    GenZappExecutors.BOUNDED.execute(() -> {
      GenZappProxyUtil.enableProxy(new GenZappProxy(trueHost, 443));

      boolean success = GenZappProxyUtil.testWebsocketConnection(TimeUnit.SECONDS.toMillis(10));

      if (success) {
        events.onNext(Event.PROXY_SUCCESS);
      } else {
        GenZappProxyUtil.disableProxy();
        events.onNext(Event.PROXY_FAILURE);
      }

      saveState.onNext(SaveState.IDLE);
    });
  }

  @NonNull Observable<UiState> getUiState() {
    return uiState.observeOn(AndroidSchedulers.mainThread());
  }

  public @NonNull Observable<Event> getEvents() {
    return events.observeOn(AndroidSchedulers.mainThread());
  }

  @NonNull Flowable<WebSocketConnectionState> getProxyState() {
    return pipeState.observeOn(AndroidSchedulers.mainThread());
  }

  public @NonNull Observable<SaveState> getSaveState() {
    return saveState.observeOn(AndroidSchedulers.mainThread());
  }

  enum UiState {
    ALL_DISABLED, ALL_ENABLED
  }

  public enum Event {
    PROXY_SUCCESS, PROXY_FAILURE
  }

  public enum SaveState {
    IDLE, IN_PROGRESS
  }
}
