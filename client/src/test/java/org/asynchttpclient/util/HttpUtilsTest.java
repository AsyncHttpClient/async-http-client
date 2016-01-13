package org.asynchttpclient.util;

import static org.testng.Assert.*;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Dsl;
import org.asynchttpclient.Request;
import org.asynchttpclient.uri.Uri;
import org.testng.annotations.Test;

public class HttpUtilsTest {

    @Test
    public void testGetAuthority() {
        Uri uri = Uri.create("http://stackoverflow.com/questions/17814461/jacoco-maven-testng-0-test-coverage");
        String authority = HttpUtils.getAuthority(uri);
        assertEquals(authority, "stackoverflow.com:80", "Incorrect authority returned from getAuthority");
    }

    @Test
    public void testGetAuthorityWithPortInUrl() {
        Uri uri = Uri.create("http://stackoverflow.com:8443/questions/17814461/jacoco-maven-testng-0-test-coverage");
        String authority = HttpUtils.getAuthority(uri);
        assertEquals(authority, "stackoverflow.com:8443", "Incorrect authority returned from getAuthority");
    }

    @Test
    public void testGetBaseUrl() {
        Uri uri = Uri.create("http://stackoverflow.com:8443/questions/17814461/jacoco-maven-testng-0-test-coverage");
        String baseUrl = HttpUtils.getBaseUrl(uri);
        assertEquals(baseUrl, "http://stackoverflow.com:8443", "Incorrect base URL returned from getBaseURL");
    }

    @Test
    public void testIsSameBaseUrlReturnsFalseWhenPortDifferent() {
        Uri uri1 = Uri.create("http://stackoverflow.com:8443/questions/17814461/jacoco-maven-testng-0-test-coverage");
        Uri uri2 = Uri.create("http://stackoverflow.com:8442/questions/1057564/pretty-git-branch-graphs");
        assertFalse(HttpUtils.isSameBase(uri1, uri2), "Base URLs should be different, but true was returned from isSameBase");
    }

    @Test
    public void testIsSameBaseUrlReturnsFalseWhenSchemeDifferent() {
        Uri uri1 = Uri.create("http://stackoverflow.com:8443/questions/17814461/jacoco-maven-testng-0-test-coverage");
        Uri uri2 = Uri.create("ws://stackoverflow.com:8443/questions/1057564/pretty-git-branch-graphs");
        assertFalse(HttpUtils.isSameBase(uri1, uri2), "Base URLs should be different, but true was returned from isSameBase");
    }

    @Test
    public void testIsSameBaseUrlReturnsFalseWhenHostDifferent() {
        Uri uri1 = Uri.create("http://stackoverflow.com:8443/questions/17814461/jacoco-maven-testng-0-test-coverage");
        Uri uri2 = Uri.create("http://example.com:8443/questions/1057564/pretty-git-branch-graphs");
        assertFalse(HttpUtils.isSameBase(uri1, uri2), "Base URLs should be different, but true was returned from isSameBase");
    }

    @Test
    public void testGetPathWhenPathIsNonEmpty() {
        Uri uri = Uri.create("http://stackoverflow.com:8443/questions/17814461/jacoco-maven-testng-0-test-coverage");
        String path = HttpUtils.getNonEmptyPath(uri);
        assertEquals(path, "/questions/17814461/jacoco-maven-testng-0-test-coverage", "Incorrect path returned from getNonEmptyPath");
    }

    @Test
    public void testGetPathWhenPathIsEmpty() {
        Uri uri = Uri.create("http://stackoverflow.com");
        String path = HttpUtils.getNonEmptyPath(uri);
        assertEquals(path, "/", "Incorrect path returned from getNonEmptyPath");
    }

    @Test
    public void testIsSameBaseUrlReturnsTrueWhenOneUriHasDefaultPort() {
        Uri uri1 = Uri.create("http://stackoverflow.com:80/questions/17814461/jacoco-maven-testng-0-test-coverage");
        Uri uri2 = Uri.create("http://stackoverflow.com/questions/1057564/pretty-git-branch-graphs");
        assertTrue(HttpUtils.isSameBase(uri1, uri2), "Base URLs should be same, but false was returned from isSameBase");
    }

    @Test
    public void testParseCharsetWithoutQuotes() {
        Charset charset = HttpUtils.parseCharset("Content-type: application/json; charset=utf-8");
        assertEquals(charset, StandardCharsets.UTF_8, "parseCharset returned wrong Charset");
    }

    @Test
    public void testParseCharsetWithSingleQuotes() {
        Charset charset = HttpUtils.parseCharset("Content-type: application/json; charset='utf-8'");
        assertEquals(charset, StandardCharsets.UTF_8, "parseCharset returned wrong Charset");
    }

    @Test
    public void testParseCharsetWithDoubleQuotes() {
        Charset charset = HttpUtils.parseCharset("Content-type: application/json; charset=\"utf-8\"");
        assertEquals(charset, StandardCharsets.UTF_8, "parseCharset returned wrong Charset");
    }

    @Test
    public void testParseCharsetReturnsNullWhenNoCharset() {
        Charset charset = HttpUtils.parseCharset("Content-type: application/json");
        assertNull(charset, "parseCharset should return null when charset is not specified in header value");
    }

    @Test
    public void testGetHostHeaderNoVirtualHost() {
        Request request = Dsl.get("http://stackoverflow.com/questions/1057564/pretty-git-branch-graphs").build();
        Uri uri = Uri.create("http://stackoverflow.com/questions/1057564/pretty-git-branch-graphs");
        String hostHeader = HttpUtils.hostHeader(request, uri);
        assertEquals(hostHeader, "stackoverflow.com", "Incorrect hostHeader returned");
    }

    @Test
    public void testGetHostHeaderHasVirtualHost() {
        Request request = Dsl.get("http://stackoverflow.com/questions/1057564").setVirtualHost("example.com").build();
        Uri uri = Uri.create("http://stackoverflow.com/questions/1057564/pretty-git-branch-graphs");
        String hostHeader = HttpUtils.hostHeader(request, uri);
        assertEquals(hostHeader, "example.com", "Incorrect hostHeader returned");
    }

    @Test
    public void testGetRequestTimeoutInRequest() {
        Request request = Dsl.get("http://stackoverflow.com/questions/1057564").setRequestTimeout(1000).build();
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder().build();
        int timeout = HttpUtils.requestTimeout(config, request);
        assertEquals(timeout, 1000, "Timeout should be taken from request when specified in builder");
    }

    @Test
    public void testGetRequestTimeoutInConfig() {
        Request request = Dsl.get("http://stackoverflow.com/questions/1057564").build();
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder().setRequestTimeout(2000).build();
        int timeout = HttpUtils.requestTimeout(config, request);
        assertEquals(timeout, 2000, "Timeout should be taken from config when not specfied in request");
    }

    @Test
    public void testGetRequestTimeoutPriortyGivenToRequest() {
        Request request = Dsl.get("http://stackoverflow.com/questions/1057564").setRequestTimeout(2300).build();
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder().setRequestTimeout(2000).build();
        int timeout = HttpUtils.requestTimeout(config, request);
        assertEquals(timeout, 2300,
                "Timeout specified in request should be given priority and timeout value should be same as value set in request");
    }

    @Test
    public void testDefaultFollowRedirect() {
        Request request = Dsl.get("http://stackoverflow.com/questions/1057564").setVirtualHost("example.com").build();
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder().build();
        boolean followRedirect = HttpUtils.followRedirect(config, request);
        assertFalse(followRedirect, "Default value of redirect should be false");
    }

    @Test
    public void testGetFollowRedirectInRequest() {
        Request request = Dsl.get("http://stackoverflow.com/questions/1057564").setFollowRedirect(true).build();
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder().build();
        boolean followRedirect = HttpUtils.followRedirect(config, request);
        assertTrue(followRedirect, "Follow redirect must be true as set in the request");
    }

    @Test
    public void testGetFollowRedirectInConfig() {
        Request request = Dsl.get("http://stackoverflow.com/questions/1057564").build();
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder().setFollowRedirect(true).build();
        boolean followRedirect = HttpUtils.followRedirect(config, request);
        assertTrue(followRedirect, "Follow redirect should be equal to value specified in config when not specified in request");
    }

    @Test
    public void testGetFollowRedirectPriorityGivenToRequest() {
        Request request = Dsl.get("http://stackoverflow.com/questions/1057564").setFollowRedirect(false).build();
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder().setFollowRedirect(true).build();
        boolean followRedirect = HttpUtils.followRedirect(config, request);
        assertFalse(followRedirect, "Follow redirect value set in request should be given priority");
    }
}
