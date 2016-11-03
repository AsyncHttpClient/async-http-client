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

import static org.testng.Assert.*;

import org.testng.annotations.Test;

public class UriParserTest {

    @Test
    public void testUrlHasLeadingAndTrailingWhiteSpace() {
        UriParser parser = new UriParser();
        parser.parse(null, "  http://user@example.com:8080/test?q=1  ");
        assertEquals(parser.authority, "user@example.com:8080", "Incorrect authority assigned by the parse method");
        assertEquals(parser.host, "example.com", "Incorrect host assigned by the parse method");
        assertEquals(parser.path, "/test", "Incorrect path assigned by the parse method");
        assertEquals(parser.port, 8080, "Incorrect port assigned by the parse method");
        assertEquals(parser.query, "q=1", "Incorrect query assigned by the parse method");
        assertEquals(parser.scheme, "http", "Incorrect scheme assigned by the parse method");
        assertEquals(parser.userInfo, "user", "Incorrect userInfo assigned by the parse method");
    }

    @Test
    public void testSchemeTakenFromUrlWhenValid() {
        Uri context = new Uri("https", null, "example.com", 80, "/path", "");
        UriParser parser = new UriParser();
        parser.parse(context, "http://example.com/path");
        assertEquals(parser.scheme, "http", "If URL has a valid scheme it should be given priority than the scheme in the context");
    }

    @Test
    public void testRelativeURL() {
        Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2");
        UriParser parser = new UriParser();
        parser.parse(context, "/relativeUrl");
        assertEquals(parser.host, "example.com", "Host should be taken from the context when parsing a relative URL");
        assertEquals(parser.port, 80, "Port should be taken from the context when parsing a relative URL");
        assertEquals(parser.scheme, "https", "Scheme should be taken from the context when parsing a relative URL");
        assertEquals(parser.path, "/relativeUrl", "Path should be equal to the relative URL passed to the parse method");
        assertEquals(parser.query, null, "Query should be empty if the relative URL did not have a query");
    }

    @Test
    public void testUrlFragment() {
        Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2");
        UriParser parser = new UriParser();
        parser.parse(context, "#test");
        assertEquals(parser.host, "example.com", "Host should be taken from the context when parsing a URL fragment");
        assertEquals(parser.port, 80, "Port should be taken from the context when parsing a URL fragment");
        assertEquals(parser.scheme, "https", "Scheme should be taken from the context when parsing a URL fragment");
        assertEquals(parser.path, "/path", "Path should be taken from the context when parsing a URL fragment");
        assertEquals(parser.query, null, "Query should be empty when parsing a URL fragment");
    }

    @Test
    public void testRelativeUrlWithQuery() {
        Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2");
        UriParser parser = new UriParser();
        parser.parse(context, "/relativePath?q=3");
        assertEquals(parser.host, "example.com", "Host should be taken from the contenxt when parsing a relative URL");
        assertEquals(parser.port, 80, "Port should be taken from the context when parsing a relative URL");
        assertEquals(parser.scheme, "https", "Scheme should be taken from the context when parsing a relative URL");
        assertEquals(parser.path, "/relativePath", "Path should be same as relativePath passed to the parse method");
        assertEquals(parser.query, "q=3", "Query should be taken from the relative URL");
    }

    @Test
    public void testRelativeUrlWithQueryOnly() {
        Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2");
        UriParser parser = new UriParser();
        parser.parse(context, "?q=3");
        assertEquals(parser.host, "example.com", "Host should be taken from the context when parsing a relative URL");
        assertEquals(parser.port, 80, "Port should be taken from the context when parsing a relative URL");
        assertEquals(parser.scheme, "https", "Scheme should be taken from the conxt when parsing a relative URL");
        assertEquals(parser.path, "/", "Path should be '/' for a relative URL with only query");
        assertEquals(parser.query, "q=3", "Query should be same as specified in the relative URL");
    }

    @Test
    public void testRelativeURLWithDots() {
        Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2");
        UriParser parser = new UriParser();
        parser.parse(context, "./relative/./url");
        assertEquals(parser.host, "example.com", "Host should be taken from the context when parsing a relative URL");
        assertEquals(parser.port, 80, "Port should be taken from the context when parsing a relative URL");
        assertEquals(parser.scheme, "https", "Scheme should be taken from the context when parsing a relative URL");
        assertEquals(parser.path, "/relative/url", "Path should be equal to the path in the relative URL with dots removed");
        assertEquals(parser.query, null, "Query should be null if the relative URL did not have a query");
    }

    @Test
    public void testRelativeURLWithTwoEmbeddedDots() {
        Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2");
        UriParser parser = new UriParser();
        parser.parse(context, "./relative/../url");
        assertEquals(parser.host, "example.com", "Host should be taken from the context when parsing a relative URL");
        assertEquals(parser.port, 80, "Port should be taken from the context when parsing a relative URL");
        assertEquals(parser.scheme, "https", "Scheme should be taken from the context when parsing a relative URL");
        assertEquals(parser.path, "/url", "Path should be equal to the relative URL path with the embedded dots appropriately removed");
        assertEquals(parser.query, null, "Query should be null if the relative URL does not have a query");
    }

    @Test
    public void testRelativeURLWithTwoTrailingDots() {
        Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2");
        UriParser parser = new UriParser();
        parser.parse(context, "./relative/url/..");
        assertEquals(parser.host, "example.com", "Host should be taken from the context when parsing a relative URL");
        assertEquals(parser.port, 80, "Port should be taken from the context when parsing a relative URL");
        assertEquals(parser.scheme, "https", "Scheme should be taken from the context when parsing a relative URL");
        assertEquals(parser.path, "/relative/", "Path should be equal to the relative URL path with the trailing dots appropriately removed");
        assertEquals(parser.query, null, "Query should be null if the relative URL does not have a query");
    }

    @Test
    public void testRelativeURLWithOneTrailingDot() {
        Uri context = new Uri("https", null, "example.com", 80, "/path", "q=2");
        UriParser parser = new UriParser();
        parser.parse(context, "./relative/url/.");
        assertEquals(parser.host, "example.com", "Host should be taken from the context when parsing a relative URL");
        assertEquals(parser.port, 80, "Port should be taken from the context when parsing a relative URL");
        assertEquals(parser.scheme, "https", "Scheme should be taken from the context when parsing a relative URL");
        assertEquals(parser.path, "/relative/url/", "Path should be equal to the relative URL path with the trailing dot appropriately removed");
        assertEquals(parser.query, null, "Query should be null if the relative URL does not have a query");
    }
}
