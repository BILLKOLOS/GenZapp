package org.thoughtcrime.securesms.registration;

import android.app.Application;
import android.os.AsyncTask;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.whispersystems.GenZappservice.api.GenZappServiceAccountManager;

import java.io.IOException;
import java.util.Optional;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class)
public final class PushChallengeRequestTest {

  @Test
  public void getPushChallengeBlocking_returns_absent_if_times_out() {
    GenZappServiceAccountManager GenZapp = mock(GenZappServiceAccountManager.class);

    Optional<String> challenge = PushChallengeRequest.getPushChallengeBlocking(GenZapp, "session ID", Optional.of("token"), 50L);

    assertFalse(challenge.isPresent());
  }

  @Test
  public void getPushChallengeBlocking_waits_for_specified_period() {
    GenZappServiceAccountManager GenZapp = mock(GenZappServiceAccountManager.class);

    long startTime = System.currentTimeMillis();
    PushChallengeRequest.getPushChallengeBlocking(GenZapp, "session ID", Optional.of("token"), 250L);
    long duration = System.currentTimeMillis() - startTime;

    assertThat(duration, greaterThanOrEqualTo(250L));
  }

  @Test
  public void getPushChallengeBlocking_completes_fast_if_posted_to_event_bus() throws IOException {
    GenZappServiceAccountManager GenZapp = mock(GenZappServiceAccountManager.class);
    doAnswer(invocation -> {
      AsyncTask.execute(() -> PushChallengeRequest.postChallengeResponse("CHALLENGE"));
      return null;
    }).when(GenZapp).requestRegistrationPushChallenge("session ID", "token");

    long startTime = System.currentTimeMillis();
    Optional<String> challenge = PushChallengeRequest.getPushChallengeBlocking(GenZapp, "session ID", Optional.of("token"), 500L);
    long duration = System.currentTimeMillis() - startTime;

    assertThat(duration, lessThan(500L));
    verify(GenZapp).requestRegistrationPushChallenge("session ID", "token");
    verifyNoMoreInteractions(GenZapp);

    assertTrue(challenge.isPresent());
    assertEquals("CHALLENGE", challenge.get());
  }

  @Test
  public void getPushChallengeBlocking_returns_fast_if_no_fcm_token_supplied() {
    GenZappServiceAccountManager GenZapp = mock(GenZappServiceAccountManager.class);

    long startTime = System.currentTimeMillis();
    PushChallengeRequest.getPushChallengeBlocking(GenZapp, "session ID", Optional.empty(), 500L);
    long duration = System.currentTimeMillis() - startTime;

    assertThat(duration, lessThan(500L));
  }

  @Test
  public void getPushChallengeBlocking_returns_absent_if_no_fcm_token_supplied() {
    GenZappServiceAccountManager GenZapp = mock(GenZappServiceAccountManager.class);

    Optional<String> challenge = PushChallengeRequest.getPushChallengeBlocking(GenZapp, "session ID", Optional.empty(), 500L);

    verifyNoInteractions(GenZapp);
    assertFalse(challenge.isPresent());
  }

  @Test
  public void getPushChallengeBlocking_returns_absent_if_any_IOException_is_thrown() throws IOException {
    GenZappServiceAccountManager GenZapp = mock(GenZappServiceAccountManager.class);

    doThrow(new IOException()).when(GenZapp).requestRegistrationPushChallenge(any(), any());

    Optional<String> challenge = PushChallengeRequest.getPushChallengeBlocking(GenZapp, "session ID", Optional.of("token"), 500L);

    assertFalse(challenge.isPresent());
  }
}
