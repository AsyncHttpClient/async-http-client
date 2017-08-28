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

import static org.testng.Assert.*;

import java.net.MalformedURLException;
import java.net.URI;

import org.testng.annotations.Test;

public class UriTest {

    private static void assertUriEquals(Uri uri, URI javaUri) {
        assertEquals(uri.getScheme(), uri.getScheme());
        assertEquals(uri.getUserInfo(), uri.getUserInfo());
        assertEquals(uri.getHost(), uri.getHost());
        assertEquals(uri.getPort(), uri.getPort());
        assertEquals(uri.getPath(), uri.getPath());
        assertEquals(uri.getQuery(), uri.getQuery());
    }

    private static void validateAgainstAbsoluteURI(String url) throws MalformedURLException {
        assertUriEquals(Uri.create(url), URI.create(url));
    }

    private static void validateAgainstRelativeURI(String context, String url) throws MalformedURLException {
        assertUriEquals(Uri.create(Uri.create(context), url), URI.create(context).resolve(URI.create(url)));
    }

    @Test
    public void testSimpleParsing() throws MalformedURLException {
        validateAgainstAbsoluteURI("https://graph.facebook.com/750198471659552/accounts/test-users?method=get&access_token=750198471659552lleveCvbUu_zqBa9tkT3tcgaPh4");
    }

    @Test
    public void testRootRelativeURIWithRootContext() throws MalformedURLException {
        validateAgainstRelativeURI("https://graph.facebook.com", "/750198471659552/accounts/test-users?method=get&access_token=750198471659552lleveCvbUu_zqBa9tkT3tcgaPh4");
    }

    @Test
    public void testRootRelativeURIWithNonRootContext() throws MalformedURLException {
        validateAgainstRelativeURI("https://graph.facebook.com/foo/bar", "/750198471659552/accounts/test-users?method=get&access_token=750198471659552lleveCvbUu_zqBa9tkT3tcgaPh4");
    }

    @Test
    public void testNonRootRelativeURIWithNonRootContext() throws MalformedURLException {
        validateAgainstRelativeURI("https://graph.facebook.com/foo/bar", "750198471659552/accounts/test-users?method=get&access_token=750198471659552lleveCvbUu_zqBa9tkT3tcgaPh4");
    }

    @Test
    public void testNonRootRelativeURIWithRootContext() throws MalformedURLException {
        validateAgainstRelativeURI("https://graph.facebook.com", "750198471659552/accounts/test-users?method=get&access_token=750198471659552lleveCvbUu_zqBa9tkT3tcgaPh4");
    }

    @Test
    public void testAbsoluteURIWithContext() throws MalformedURLException {
        validateAgainstRelativeURI("https://hello.com/foo/bar",
                "https://graph.facebook.com/750198471659552/accounts/test-users?method=get&access_token=750198471659552lleveCvbUu_zqBa9tkT3tcgaPh4");
    }

    @Test
    public void testRelativeUriWithDots() throws MalformedURLException {
        validateAgainstRelativeURI("https://hello.com/level1/level2/", "../other/content/img.png");
    }

    @Test
    public void testRelativeUriWithDotsAboveRoot() throws MalformedURLException {
        validateAgainstRelativeURI("https://hello.com/level1", "../other/content/img.png");
    }

    @Test
    public void testRelativeUriWithAbsoluteDots() throws MalformedURLException {
        validateAgainstRelativeURI("https://hello.com/level1/", "/../other/content/img.png");
    }

    @Test
    public void testRelativeUriWithConsecutiveDots() throws MalformedURLException {
        validateAgainstRelativeURI("https://hello.com/level1/level2/", "../../other/content/img.png");
    }

    @Test
    public void testRelativeUriWithConsecutiveDotsAboveRoot() throws MalformedURLException {
        validateAgainstRelativeURI("https://hello.com/level1/level2", "../../other/content/img.png");
    }

    @Test
    public void testRelativeUriWithAbsoluteConsecutiveDots() throws MalformedURLException {
        validateAgainstRelativeURI("https://hello.com/level1/level2/", "/../../other/content/img.png");
    }

    @Test
    public void testRelativeUriWithConsecutiveDotsFromRoot() throws MalformedURLException {
        validateAgainstRelativeURI("https://hello.com/", "../../../other/content/img.png");
    }

    @Test
    public void testRelativeUriWithConsecutiveDotsFromRootResource() throws MalformedURLException {
        validateAgainstRelativeURI("https://hello.com/level1", "../../../other/content/img.png");
    }

    @Test
    public void testRelativeUriWithConsecutiveDotsFromSubrootResource() throws MalformedURLException {
        validateAgainstRelativeURI("https://hello.com/level1/level2", "../../../other/content/img.png");
    }

    @Test
    public void testRelativeUriWithConsecutiveDotsFromLevel3Resource() throws MalformedURLException {
        validateAgainstRelativeURI("https://hello.com/level1/level2/level3", "../../../other/content/img.png");
    }

    @Test
    public void testRelativeUriWithNoScheme() throws MalformedURLException {
        validateAgainstRelativeURI("https://hello.com/level1", "//world.org/content/img.png");
    }

    @Test
    public void testCreateAndToUrl() {
        String url = "https://hello.com/level1/level2/level3";
        Uri uri = Uri.create(url);
        assertEquals(uri.toUrl(), url, "url used to create uri and url returned from toUrl do not match");
    }

    @Test
    public void testToUrlWithUserInfoPortPathAndQuery() {
        Uri uri = new Uri("http", "user", "example.com", 44, "/path/path2", "query=4");
        assertEquals(uri.toUrl(), "http://user@example.com:44/path/path2?query=4", "toUrl returned incorrect url");
    }

    @Test
    public void testQueryWithNonRootPath() {
        Uri uri = Uri.create("http://hello.com/foo?query=value");
        assertEquals(uri.getPath(), "/foo");
        assertEquals(uri.getQuery(), "query=value");
    }

    @Test
    public void testQueryWithNonRootPathAndTrailingSlash() {
        Uri uri = Uri.create("http://hello.com/foo/?query=value");
        assertEquals(uri.getPath(), "/foo/");
        assertEquals(uri.getQuery(), "query=value");
    }

    @Test
    public void testQueryWithRootPath() {
        Uri uri = Uri.create("http://hello.com?query=value");
        assertEquals(uri.getPath(), "");
        assertEquals(uri.getQuery(), "query=value");
    }

    @Test
    public void testQueryWithRootPathAndTrailingSlash() {
        Uri uri = Uri.create("http://hello.com/?query=value");
        assertEquals(uri.getPath(), "/");
        assertEquals(uri.getQuery(), "query=value");
    }

    @Test
    public void testWithNewScheme() {
        Uri uri = new Uri("http", "user", "example.com", 44, "/path/path2", "query=4");
        Uri newUri = uri.withNewScheme("https");
        assertEquals(newUri.getScheme(), "https");
        assertEquals(newUri.toUrl(), "https://user@example.com:44/path/path2?query=4", "toUrl returned incorrect url");
    }

    @Test
    public void testWithNewQuery() {
        Uri uri = new Uri("http", "user", "example.com", 44, "/path/path2", "query=4");
        Uri newUri = uri.withNewQuery("query2=10&query3=20");
        assertEquals(newUri.getQuery(), "query2=10&query3=20");
        assertEquals(newUri.toUrl(), "http://user@example.com:44/path/path2?query2=10&query3=20", "toUrl returned incorrect url");
    }

    @Test
    public void testToRelativeUrl() {
        Uri uri = new Uri("http", "user", "example.com", 44, "/path/path2", "query=4");
        String relativeUrl = uri.toRelativeUrl();
        assertEquals(relativeUrl, "/path/path2?query=4", "toRelativeUrl returned incorrect url");
    }

    @Test
    public void testToRelativeUrlWithEmptyPath() {
        Uri uri = new Uri("http", "user", "example.com", 44, null, "query=4");
        String relativeUrl = uri.toRelativeUrl();
        assertEquals(relativeUrl, "/?query=4", "toRelativeUrl returned incorrect url");
    }

    @Test
    public void testGetSchemeDefaultPortHttpScheme() {
        String url = "https://hello.com/level1/level2/level3";
        Uri uri = Uri.create(url);
        assertEquals(uri.getSchemeDefaultPort(), 443, "schema default port should be 443 for https url");

        String url2 = "http://hello.com/level1/level2/level3";
        Uri uri2 = Uri.create(url2);
        assertEquals(uri2.getSchemeDefaultPort(), 80, "schema default port should be 80 for http url");
    }

    @Test
    public void testGetSchemeDefaultPortWebSocketScheme() {
        String url = "wss://hello.com/level1/level2/level3";
        Uri uri = Uri.create(url);
        assertEquals(uri.getSchemeDefaultPort(), 443, "schema default port should be 443 for wss url");

        String url2 = "ws://hello.com/level1/level2/level3";
        Uri uri2 = Uri.create(url2);
        assertEquals(uri2.getSchemeDefaultPort(), 80, "schema default port should be 80 for ws url");
    }

    @Test
    public void testGetExplicitPort() {
        String url = "http://hello.com/level1/level2/level3";
        Uri uri = Uri.create(url);
        assertEquals(uri.getExplicitPort(), 80, "getExplicitPort should return port 80 for http url when port is not specified in url");

        String url2 = "http://hello.com:8080/level1/level2/level3";
        Uri uri2 = Uri.create(url2);
        assertEquals(uri2.getExplicitPort(), 8080, "getExplicitPort should return the port given in the url");
    }

    @Test
    public void testEquals() {
        String url = "http://user@hello.com:8080/level1/level2/level3?q=1";
        Uri createdUri = Uri.create(url);
        Uri constructedUri = new Uri("http", "user", "hello.com", 8080, "/level1/level2/level3", "q=1");
        assertTrue(createdUri.equals(constructedUri), "The equals method returned false for two equal urls");
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

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void creatingUriWithMissingSchemeThrowsIllegalArgumentException() {
        Uri.create("localhost");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void creatingUriWithMissingHostThrowsIllegalArgumentException() {
        Uri.create("http://");
    }
}
