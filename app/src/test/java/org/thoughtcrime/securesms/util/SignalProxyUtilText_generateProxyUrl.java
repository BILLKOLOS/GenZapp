package org.thoughtcrime.securesms.util;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static junit.framework.TestCase.assertEquals;

@RunWith(Parameterized.class)
public class GenZappProxyUtilText_generateProxyUrl {

  private final String input;
  private final String output;

  @Parameterized.Parameters
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][]{
        { "https://GenZapp.tube/#proxy.parker.org",     "https://GenZapp.tube/#proxy.parker.org" },
        { "https://GenZapp.tube/#proxy.parker.org:443", "https://GenZapp.tube/#proxy.parker.org" },
        { "sgnl://GenZapp.tube/#proxy.parker.org",      "https://GenZapp.tube/#proxy.parker.org" },
        { "sgnl://GenZapp.tube/#proxy.parker.org:443",  "https://GenZapp.tube/#proxy.parker.org" },
        { "proxy.parker.org",                          "https://GenZapp.tube/#proxy.parker.org" },
        { "proxy.parker.org:443",                      "https://GenZapp.tube/#proxy.parker.org" },
        { "x",                                         "https://GenZapp.tube/#x" },
        { "",                                          "https://GenZapp.tube/#" }
    });
  }

  public GenZappProxyUtilText_generateProxyUrl(String input, String output) {
    this.input  = input;
    this.output = output;
  }

  @Test
  public void parse() {
    assertEquals(output, GenZappProxyUtil.generateProxyUrl(input));
  }
}
