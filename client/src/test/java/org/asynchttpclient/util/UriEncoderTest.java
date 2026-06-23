/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.util;

import org.asynchttpclient.Param;
import org.asynchttpclient.uri.Uri;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests {@link UriEncoder#encode(Uri, List)}'s fast path: when an already-encoded URL is encoded with no
 * extra query params, encoding changes nothing, so the SAME Uri instance is returned (no wasted copy).
 * When anything actually changes (added params, or a path/query needing escaping), a new equal-or-encoded
 * Uri is returned.
 */
public class UriEncoderTest {

    private static final UriEncoder FIXING = UriEncoder.uriEncoder(false);
    private static final UriEncoder RAW = UriEncoder.uriEncoder(true);

    @Test
    public void returnsSameInstanceWhenNothingChanges() {
        Uri uri = Uri.create("http://www.example.com/path/to/resource?a=1&b=2");
        assertSame(uri, FIXING.encode(uri, null), "clean URL + no params should reuse the input Uri");
        assertSame(uri, FIXING.encode(uri, Collections.emptyList()), "empty param list should also reuse it");
        assertSame(uri, RAW.encode(uri, null), "RAW on a clean URL should reuse the input Uri");
    }

    @Test
    public void returnsSameInstanceWhenNoQueryAndNoParams() {
        Uri uri = Uri.create("http://www.example.com/path");
        assertSame(uri, FIXING.encode(uri, null));
    }

    @Test
    public void rebuildsWhenQueryParamsAdded() {
        Uri uri = Uri.create("http://www.example.com/path?a=1");
        List<Param> params = Collections.singletonList(new Param("b", "2"));
        Uri encoded = FIXING.encode(uri, params);
        assertNotSame(uri, encoded, "adding a query param must produce a new Uri");
        assertEquals("a=1&b=2", encoded.getQuery());
        // Untouched fields are preserved.
        assertEquals(uri.getScheme(), encoded.getScheme());
        assertEquals(uri.getHost(), encoded.getHost());
        assertEquals(uri.getPath(), encoded.getPath());
    }

    @Test
    public void rebuildsWhenPathNeedsEncoding() {
        // A space in the path must be percent-encoded by FIXING, so the result differs from the input.
        Uri uri = Uri.create("http://www.example.com/a b");
        Uri encoded = FIXING.encode(uri, null);
        assertNotSame(uri, encoded, "a path needing escaping must produce a new Uri");
        assertEquals("/a%20b", encoded.getPath());
    }
}
