/*
 *    Copyright (c) 2018-2023 AsyncHttpClient Project. All rights reserved.
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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

public class Utf8UrlEncoderTest {

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testBasics() {
        assertEquals("foobar", Utf8UrlEncoder.encodeQueryElement("foobar"));
        assertEquals("a%26b", Utf8UrlEncoder.encodeQueryElement("a&b"));
        assertEquals("a%2Bb", Utf8UrlEncoder.encodeQueryElement("a+b"));
    }

    @Test
    public void encodeQueryReusesInputWhenNothingNeedsEscaping() {
        String query = "a=1&b=/two?c%20d";

        assertSame(query, Utf8UrlEncoder.encodeQuery(query));
    }

    @Test
    public void encodeQueryEscapesWhenNeeded() {
        String query = "a=one two";
        String encoded = Utf8UrlEncoder.encodeQuery(query);

        assertNotSame(query, encoded);
        assertEquals("a=one%20two", encoded);
        assertEquals("a=%C3%A9", Utf8UrlEncoder.encodeQuery("a=\u00e9"));
    }

    @Test
    public void testPercentageEncoding() {
        assertEquals("foobar", Utf8UrlEncoder.percentEncodeQueryElement("foobar"));
        assertEquals("foo%2Abar", Utf8UrlEncoder.percentEncodeQueryElement("foo*bar"));
        assertEquals("foo~b_ar", Utf8UrlEncoder.percentEncodeQueryElement("foo~b_ar"));
    }
}
