/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.uri;

import io.github.artsok.RepeatedIfExceptionsTest;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class UriParserTest {

    private static void assertUriEquals(UriParser parser, URI uri) {
        assertEquals(uri.getScheme(), parser.scheme);
        assertEquals(uri.getUserInfo(), parser.userInfo);
        assertEquals(uri.getHost(), parser.host);
        assertEquals(uri.getPort(), parser.port);
        assertEquals(uri.getPath(), parser.path);
        assertEquals(uri.getQuery(), parser.query);
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

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testUrlWithPathAndQuery() {
        validateAgainstAbsoluteURI("http://example.com:8080/test?q=1");
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testFragmentTryingToTrickAuthorityAsBasicAuthCredentials() {
        validateAgainstAbsoluteURI("http://1.2.3.4:81#@5.6.7.8:82/aaa/b?q=xxx");
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testUrlHasLeadingAndTrailingWhiteSpace() {
        UriParser parser = new UriParser();
        String url = "  http://user@example.com:8080/test?q=1  ";
        parser.parse(null, url);
        assertUriEquals(parser, URI.create(url.trim()));
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testResolveAbsoluteUriAgainstContext() {
        Uri context = new Uri("https", null, "example.com", 80, "/path", "", null);
        validateAgainstRelativeURI(context, "https://example.com:80/path", "http://example.com/path");
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testRootRelativePath() {
        Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2", null);
        validateAgainstRelativeURI(context, "https://example.com:80/path?q=2", "/relativeUrl");
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testCurrentDirRelativePath() {
        Uri context = new Uri("https", null, "example.com", 80, "/foo/bar", "q=2", null);
        validateAgainstRelativeURI(context, "https://example.com:80/foo/bar?q=2", "relativeUrl");
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testFragmentOnly() {
        Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2", null);
        validateAgainstRelativeURI(context, "https://example.com:80/path?q=2", "#test");
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testRelativeUrlWithQuery() {
        Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2", null);
        validateAgainstRelativeURI(context, "https://example.com:80/path?q=2", "/relativePath?q=3");
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testRelativeUrlWithQueryOnly() {
        Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2", null);
        validateAgainstRelativeURI(context, "https://example.com:80/path?q=2", "?q=3");
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testRelativeURLWithDots() {
        Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2", null);
        validateAgainstRelativeURI(context, "https://example.com:80/path?q=2", "./relative/./url");
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testRelativeURLWithTwoEmbeddedDots() {
        Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2", null);
        validateAgainstRelativeURI(context, "https://example.com:80/path?q=2", "./relative/../url");
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testRelativeURLWithTwoTrailingDots() {
        Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2", null);
        validateAgainstRelativeURI(context, "https://example.com:80/path?q=2", "./relative/url/..");
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testRelativeURLWithOneTrailingDot() {
        Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2", null);
        validateAgainstRelativeURI(context, "https://example.com:80/path?q=2", "./relative/url/.");
    }
}
