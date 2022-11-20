/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.uri;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UriTest {

    private static void assertUriEquals(Uri uri, URI javaUri) {
        assertEquals(javaUri.getScheme(), uri.getScheme());
        assertEquals(javaUri.getUserInfo(), uri.getUserInfo());
        assertEquals(javaUri.getHost(), uri.getHost());
        assertEquals(javaUri.getPort(), uri.getPort());
        assertEquals(javaUri.getPath(), uri.getPath());
        assertEquals(javaUri.getQuery(), uri.getQuery());
    }

    private static void validateAgainstAbsoluteURI(String url) {
        assertUriEquals(Uri.create(url), URI.create(url));
    }

    private static void validateAgainstRelativeURI(String context, String url) {
        assertUriEquals(Uri.create(Uri.create(context), url), URI.create(context).resolve(URI.create(url)));
    }

    @Test
    public void testSimpleParsing() {
        validateAgainstAbsoluteURI("https://graph.facebook.com/750198471659552/accounts/test-users?method=get&access_token=750198471659552lleveCvbUu_zqBa9tkT3tcgaPh4");
    }

    @Test
    public void testRootRelativeURIWithRootContext() {
        validateAgainstRelativeURI("https://graph.facebook.com", "/750198471659552/accounts/test-users?method=get&access_token=750198471659552lleveCvbUu_zqBa9tkT3tcgaPh4");
    }

    @Test
    public void testRootRelativeURIWithNonRootContext() {
        validateAgainstRelativeURI("https://graph.facebook.com/foo/bar", "/750198471659552/accounts/test-users?method=get&access_token=750198471659552lleveCvbUu_zqBa9tkT3tcgaPh4");
    }

    @Test
    public void testNonRootRelativeURIWithNonRootContext() {
        validateAgainstRelativeURI("https://graph.facebook.com/foo/bar", "750198471659552/accounts/test-users?method=get&access_token=750198471659552lleveCvbUu_zqBa9tkT3tcgaPh4");
    }

    @Disabled
    @Test
    // FIXME weird: java.net.URI#getPath return "750198471659552/accounts/test-users" without a "/"?!
    public void testNonRootRelativeURIWithRootContext() {
        validateAgainstRelativeURI("https://graph.facebook.com", "750198471659552/accounts/test-users?method=get&access_token=750198471659552lleveCvbUu_zqBa9tkT3tcgaPh4");
    }

    @Test
    public void testAbsoluteURIWithContext() {
        validateAgainstRelativeURI("https://hello.com/foo/bar",
                "https://graph.facebook.com/750198471659552/accounts/test-users?method=get&access_token=750198471659552lleveCvbUu_zqBa9tkT3tcgaPh4");
    }

    @Test
    public void testRelativeUriWithDots() {
        validateAgainstRelativeURI("https://hello.com/level1/level2/", "../other/content/img.png");
    }

    @Test
    public void testRelativeUriWithDotsAboveRoot() {
        validateAgainstRelativeURI("https://hello.com/level1", "../other/content/img.png");
    }

    @Test
    public void testRelativeUriWithAbsoluteDots() {
        validateAgainstRelativeURI("https://hello.com/level1/", "/../other/content/img.png");
    }

    @Test
    public void testRelativeUriWithConsecutiveDots() {
        validateAgainstRelativeURI("https://hello.com/level1/level2/", "../../other/content/img.png");
    }

    @Test
    public void testRelativeUriWithConsecutiveDotsAboveRoot() {
        validateAgainstRelativeURI("https://hello.com/level1/level2", "../../other/content/img.png");
    }

    @Test
    public void testRelativeUriWithAbsoluteConsecutiveDots() {
        validateAgainstRelativeURI("https://hello.com/level1/level2/", "/../../other/content/img.png");
    }

    @Test
    public void testRelativeUriWithConsecutiveDotsFromRoot() {
        validateAgainstRelativeURI("https://hello.com/", "../../../other/content/img.png");
    }

    @Test
    public void testRelativeUriWithConsecutiveDotsFromRootResource() {
        validateAgainstRelativeURI("https://hello.com/level1", "../../../other/content/img.png");
    }

    @Test
    public void testRelativeUriWithConsecutiveDotsFromSubrootResource() {
        validateAgainstRelativeURI("https://hello.com/level1/level2", "../../../other/content/img.png");
    }

    @Test
    public void testRelativeUriWithConsecutiveDotsFromLevel3Resource() {
        validateAgainstRelativeURI("https://hello.com/level1/level2/level3", "../../../other/content/img.png");
    }

    @Test
    public void testRelativeUriWithNoScheme() {
        validateAgainstRelativeURI("https://hello.com/level1", "//world.org/content/img.png");
    }

    @Test
    public void testCreateAndToUrl() {
        String url = "https://hello.com/level1/level2/level3";
        Uri uri = Uri.create(url);
        assertEquals(url, uri.toUrl(), "url used to create uri and url returned from toUrl do not match");
    }

    @Test
    public void testToUrlWithUserInfoPortPathAndQuery() {
        Uri uri = new Uri("http", "user", "example.com", 44, "/path/path2", "query=4", null);
        assertEquals("http://user@example.com:44/path/path2?query=4", uri.toUrl(), "toUrl returned incorrect url");
    }

    @Test
    public void testQueryWithNonRootPath() {
        Uri uri = Uri.create("http://hello.com/foo?query=value");
        assertEquals("/foo", uri.getPath());
        assertEquals("query=value", uri.getQuery());
    }

    @Test
    public void testQueryWithNonRootPathAndTrailingSlash() {
        Uri uri = Uri.create("http://hello.com/foo/?query=value");
        assertEquals("/foo/", uri.getPath());
        assertEquals("query=value", uri.getQuery());
    }

    @Test
    public void testQueryWithRootPath() {
        Uri uri = Uri.create("http://hello.com?query=value");
        assertEquals("", uri.getPath());
        assertEquals("query=value", uri.getQuery());
    }

    @Test
    public void testQueryWithRootPathAndTrailingSlash() {
        Uri uri = Uri.create("http://hello.com/?query=value");
        assertEquals("/", uri.getPath());
        assertEquals("query=value", uri.getQuery());
    }

    @Test
    public void testWithNewScheme() {
        Uri uri = new Uri("http", "user", "example.com", 44, "/path/path2", "query=4", null);
        Uri newUri = uri.withNewScheme("https");
        assertEquals("https", newUri.getScheme());
        assertEquals("https://user@example.com:44/path/path2?query=4", newUri.toUrl(), "toUrl returned incorrect url");
    }

    @Test
    public void testWithNewQuery() {
        Uri uri = new Uri("http", "user", "example.com", 44, "/path/path2", "query=4", null);
        Uri newUri = uri.withNewQuery("query2=10&query3=20");
        assertEquals(newUri.getQuery(), "query2=10&query3=20");
        assertEquals("http://user@example.com:44/path/path2?query2=10&query3=20", newUri.toUrl(), "toUrl returned incorrect url");
    }

    @Test
    public void testToRelativeUrl() {
        Uri uri = new Uri("http", "user", "example.com", 44, "/path/path2", "query=4", null);
        String relativeUrl = uri.toRelativeUrl();
        assertEquals("/path/path2?query=4", relativeUrl, "toRelativeUrl returned incorrect url");
    }

    @Test
    public void testToRelativeUrlWithEmptyPath() {
        Uri uri = new Uri("http", "user", "example.com", 44, null, "query=4", null);
        String relativeUrl = uri.toRelativeUrl();
        assertEquals("/?query=4", relativeUrl, "toRelativeUrl returned incorrect url");
    }

    @Test
    public void testGetSchemeDefaultPortHttpScheme() {
        String url = "https://hello.com/level1/level2/level3";
        Uri uri = Uri.create(url);
        assertEquals(443, uri.getSchemeDefaultPort(), "schema default port should be 443 for https url");

        String url2 = "http://hello.com/level1/level2/level3";
        Uri uri2 = Uri.create(url2);
        assertEquals(80, uri2.getSchemeDefaultPort(), "schema default port should be 80 for http url");
    }

    @Test
    public void testGetSchemeDefaultPortWebSocketScheme() {
        String url = "wss://hello.com/level1/level2/level3";
        Uri uri = Uri.create(url);
        assertEquals(443, uri.getSchemeDefaultPort(), "schema default port should be 443 for wss url");

        String url2 = "ws://hello.com/level1/level2/level3";
        Uri uri2 = Uri.create(url2);
        assertEquals(80, uri2.getSchemeDefaultPort(), "schema default port should be 80 for ws url");
    }

    @Test
    public void testGetExplicitPort() {
        String url = "http://hello.com/level1/level2/level3";
        Uri uri = Uri.create(url);
        assertEquals(80, uri.getExplicitPort(), "getExplicitPort should return port 80 for http url when port is not specified in url");

        String url2 = "http://hello.com:8080/level1/level2/level3";
        Uri uri2 = Uri.create(url2);
        assertEquals(8080, uri2.getExplicitPort(), "getExplicitPort should return the port given in the url");
    }

    @Test
    public void testEquals() {
        String url = "http://user@hello.com:8080/level1/level2/level3?q=1";
        Uri createdUri = Uri.create(url);
        Uri constructedUri = new Uri("http", "user", "hello.com", 8080, "/level1/level2/level3", "q=1", null);
        assertEquals(createdUri, constructedUri, "The equals method returned false for two equal urls");
    }

    @Test
    void testFragment() {
        String url = "http://user@hello.com:8080/level1/level2/level3?q=1";
        String fragment = "foo";
        String urlWithFragment = url + '#' + fragment;
        Uri uri = Uri.create(urlWithFragment);
        assertEquals(uri.getFragment(), fragment, "Fragment should be extracted");
        assertEquals(url, uri.toUrl(), "toUrl should return without fragment");
        assertEquals(urlWithFragment, uri.toFullUrl(), "toFullUrl should return with fragment");
    }

    @Test
    void testRelativeFragment() {
        Uri uri = Uri.create(Uri.create("http://user@hello.com:8080"), "/level1/level2/level3?q=1#foo");
        assertEquals("foo", uri.getFragment(), "fragment should be kept when computing a relative url");
    }

    @Test
    public void testIsWebsocket() {
        String url = "http://user@hello.com:8080/level1/level2/level3?q=1";
        Uri uri = Uri.create(url);
        assertFalse(uri.isWebSocket(), "isWebSocket should return false for http url");

        url = "https://user@hello.com:8080/level1/level2/level3?q=1";
        uri = Uri.create(url);
        assertFalse(uri.isWebSocket(), "isWebSocket should return false for https url");

        url = "ws://user@hello.com:8080/level1/level2/level3?q=1";
        uri = Uri.create(url);
        assertTrue(uri.isWebSocket(), "isWebSocket should return true for ws url");

        url = "wss://user@hello.com:8080/level1/level2/level3?q=1";
        uri = Uri.create(url);
        assertTrue(uri.isWebSocket(), "isWebSocket should return true for wss url");
    }

    @Test
    public void creatingUriWithDefinedSchemeAndHostWorks() {
        Uri.create("http://localhost");
    }

    @Test
    public void creatingUriWithMissingSchemeThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> Uri.create("localhost"));
    }

    @Test
    public void creatingUriWithMissingHostThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> Uri.create("http://"));
    }

    @Test
    public void testGetAuthority() {
        Uri uri = Uri.create("http://stackoverflow.com/questions/17814461/jacoco-maven-testng-0-test-coverage");
        assertEquals("stackoverflow.com:80", uri.getAuthority(), "Incorrect authority returned from getAuthority");
    }

    @Test
    public void testGetAuthorityWithPortInUrl() {
        Uri uri = Uri.create("http://stackoverflow.com:8443/questions/17814461/jacoco-maven-testng-0-test-coverage");
        assertEquals("stackoverflow.com:8443", uri.getAuthority(), "Incorrect authority returned from getAuthority");
    }

    @Test
    public void testGetBaseUrl() {
        Uri uri = Uri.create("http://stackoverflow.com:8443/questions/17814461/jacoco-maven-testng-0-test-coverage");
        assertEquals("http://stackoverflow.com:8443", uri.getBaseUrl(), "Incorrect base URL returned from getBaseURL");
    }

    @Test
    public void testIsSameBaseUrlReturnsFalseWhenPortDifferent() {
        Uri uri1 = Uri.create("http://stackoverflow.com:8443/questions/17814461/jacoco-maven-testng-0-test-coverage");
        Uri uri2 = Uri.create("http://stackoverflow.com:8442/questions/1057564/pretty-git-branch-graphs");
        assertFalse(uri1.isSameBase(uri2), "Base URLs should be different, but true was returned from isSameBase");
    }

    @Test
    public void testIsSameBaseUrlReturnsFalseWhenSchemeDifferent() {
        Uri uri1 = Uri.create("http://stackoverflow.com:8443/questions/17814461/jacoco-maven-testng-0-test-coverage");
        Uri uri2 = Uri.create("ws://stackoverflow.com:8443/questions/1057564/pretty-git-branch-graphs");
        assertFalse(uri1.isSameBase(uri2), "Base URLs should be different, but true was returned from isSameBase");
    }

    @Test
    public void testIsSameBaseUrlReturnsFalseWhenHostDifferent() {
        Uri uri1 = Uri.create("http://stackoverflow.com:8443/questions/17814461/jacoco-maven-testng-0-test-coverage");
        Uri uri2 = Uri.create("http://example.com:8443/questions/1057564/pretty-git-branch-graphs");
        assertFalse(uri1.isSameBase(uri2), "Base URLs should be different, but true was returned from isSameBase");
    }

    @Test
    public void testIsSameBaseUrlReturnsTrueWhenOneUriHasDefaultPort() {
        Uri uri1 = Uri.create("http://stackoverflow.com:80/questions/17814461/jacoco-maven-testng-0-test-coverage");
        Uri uri2 = Uri.create("http://stackoverflow.com/questions/1057564/pretty-git-branch-graphs");
        assertTrue(uri1.isSameBase(uri2), "Base URLs should be same, but false was returned from isSameBase");
    }

    @Test
    public void testGetPathWhenPathIsNonEmpty() {
        Uri uri = Uri.create("http://stackoverflow.com:8443/questions/17814461/jacoco-maven-testng-0-test-coverage");
        assertEquals("/questions/17814461/jacoco-maven-testng-0-test-coverage", uri.getNonEmptyPath(), "Incorrect path returned from getNonEmptyPath");
    }

    @Test
    public void testGetPathWhenPathIsEmpty() {
        Uri uri = Uri.create("http://stackoverflow.com");
        assertEquals("/", uri.getNonEmptyPath(), "Incorrect path returned from getNonEmptyPath");
    }
}
