package org.thoughtcrime.securesms;

import org.GenZapp.libGenZapp.zkgroup.ServerPublicParams;
import org.GenZapp.libGenZapp.zkgroup.ServerSecretParams;
import org.GenZapp.libGenZapp.zkgroup.VerificationFailedException;
import org.GenZapp.libGenZapp.zkgroup.groups.GroupPublicParams;
import org.GenZapp.libGenZapp.zkgroup.profiles.ProfileKeyCommitment;
import org.GenZapp.libGenZapp.zkgroup.profiles.ProfileKeyCredentialPresentation;
import org.GenZapp.libGenZapp.zkgroup.profiles.ProfileKeyCredentialRequest;
import org.GenZapp.libGenZapp.zkgroup.profiles.ServerZkProfileOperations;
import org.whispersystems.GenZappservice.test.LibGenZappLibraryUtil;

import java.util.UUID;

/**
 * Provides Zk group operations that the server would provide.
 * Copied in app from libGenZapp
 */
public final class TestZkGroupServer {

  private final ServerPublicParams        serverPublicParams;
  private final ServerZkProfileOperations serverZkProfileOperations;

  public TestZkGroupServer() {
    LibGenZappLibraryUtil.assumeLibGenZappSupportedOnOS();

    ServerSecretParams serverSecretParams = ServerSecretParams.generate();

    serverPublicParams        = serverSecretParams.getPublicParams();
    serverZkProfileOperations = new ServerZkProfileOperations(serverSecretParams);
  }

  public ServerPublicParams getServerPublicParams() {
    return serverPublicParams;
  }
}
