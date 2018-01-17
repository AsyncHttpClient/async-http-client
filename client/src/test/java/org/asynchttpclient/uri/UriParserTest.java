/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.uri;

import org.testng.annotations.Test;

import java.net.URI;

import static org.testng.Assert.assertEquals;

public class UriParserTest {

  private static void assertUriEquals(UriParser parser, URI uri) {
    assertEquals(parser.scheme, uri.getScheme());
    assertEquals(parser.userInfo, uri.getUserInfo());
    assertEquals(parser.host, uri.getHost());
    assertEquals(parser.port, uri.getPort());
    assertEquals(parser.path, uri.getPath());
    assertEquals(parser.query, uri.getQuery());
  }

  private static void validateAgainstAbsoluteURI(String url) {
    UriParser parser = new UriParser();
    parser.parse(null, url);
    assertUriEquals(parser, URI.create(url));
  }

  private static void validateAgainstRelativeURI(Uri uriContext, String urlContext, String url) {
    UriParser parser = new UriParser();
    parser.parse(uriContext, url);
    assertUriEquals(parser, URI.create(urlContext).resolve(URI.create(url)));
  }

  @Test
  public void testUrlWithPathAndQuery() {
    validateAgainstAbsoluteURI("http://example.com:8080/test?q=1");
  }

  @Test
  public void testFragmentTryingToTrickAuthorityAsBasicAuthCredentials() {
    validateAgainstAbsoluteURI("http://1.2.3.4:81#@5.6.7.8:82/aaa/b?q=xxx");
  }

  @Test
  public void testUrlHasLeadingAndTrailingWhiteSpace() {
    UriParser parser = new UriParser();
    String url = "  http://user@example.com:8080/test?q=1  ";
    parser.parse(null, url);
    assertUriEquals(parser, URI.create(url.trim()));
  }

  @Test
  public void testResolveAbsoluteUriAgainstContext() {
    Uri context = new Uri("https", null, "example.com", 80, "/path", "");
    validateAgainstRelativeURI(context, "https://example.com:80/path", "http://example.com/path");
  }

  @Test
  public void testRootRelativePath() {
    Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2");
    validateAgainstRelativeURI(context, "https://example.com:80/path?q=2", "/relativeUrl");
  }

  @Test
  public void testCurrentDirRelativePath() {
    Uri context = new Uri("https", null, "example.com", 80, "/foo/bar", "q=2");
    validateAgainstRelativeURI(context, "https://example.com:80/foo/bar?q=2", "relativeUrl");
  }

  @Test
  public void testFragmentOnly() {
    Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2");
    validateAgainstRelativeURI(context, "https://example.com:80/path?q=2", "#test");
  }

  @Test
  public void testRelativeUrlWithQuery() {
    Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2");
    validateAgainstRelativeURI(context, "https://example.com:80/path?q=2", "/relativePath?q=3");
  }

  @Test
  public void testRelativeUrlWithQueryOnly() {
    Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2");
    validateAgainstRelativeURI(context, "https://example.com:80/path?q=2", "?q=3");
  }

  @Test
  public void testRelativeURLWithDots() {
    Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2");
    validateAgainstRelativeURI(context, "https://example.com:80/path?q=2", "./relative/./url");
  }

  @Test
  public void testRelativeURLWithTwoEmbeddedDots() {
    Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2");
    validateAgainstRelativeURI(context, "https://example.com:80/path?q=2", "./relative/../url");
  }

  @Test
  public void testRelativeURLWithTwoTrailingDots() {
    Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2");
    validateAgainstRelativeURI(context, "https://example.com:80/path?q=2", "./relative/url/..");
  }

  @Test
  public void testRelativeURLWithOneTrailingDot() {
    Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2");
    validateAgainstRelativeURI(context, "https://example.com:80/path?q=2", "./relative/url/.");
  }
}
