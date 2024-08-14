package org.thoughtcrime.securesms.util;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static junit.framework.TestCase.assertEquals;

@RunWith(Parameterized.class)
public class GenZappProxyUtilText_parseHostFromProxyDeepLink {

  private final String input;
  private final String output;

  @Parameterized.Parameters
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][]{
        { "https://GenZapp.tube/#proxy.parker.org",     "proxy.parker.org" },
        { "https://GenZapp.tube/#proxy.parker.org:443", "proxy.parker.org" },
        { "sgnl://GenZapp.tube/#proxy.parker.org",      "proxy.parker.org" },
        { "sgnl://GenZapp.tube/#proxy.parker.org:443",  "proxy.parker.org" },
        { "https://GenZapp.tube/",                       null },
        { "https://GenZapp.tube/#",                      null },
        { "sgnl://GenZapp.tube/",                        null },
        { "sgnl://GenZapp.tube/#",                       null },
        { "http://GenZapp.tube/#proxy.parker.org",       null },
        { "GenZapp.tube/#proxy.parker.org",              null },
        { "",                                           null },
        { null,                                         null }
    });
  }

  public GenZappProxyUtilText_parseHostFromProxyDeepLink(String input, String output) {
    this.input  = input;
    this.output = output;
  }

  @Test
  public void parse() {
    assertEquals(output, GenZappProxyUtil.parseHostFromProxyDeepLink(input));
  }
}
