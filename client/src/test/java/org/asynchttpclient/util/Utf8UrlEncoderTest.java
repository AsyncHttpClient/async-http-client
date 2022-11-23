/*
 * Copyright (c) 2018 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.util;

import io.github.artsok.RepeatedIfExceptionsTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class Utf8UrlEncoderTest {

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testBasics() {
        assertEquals("foobar", Utf8UrlEncoder.encodeQueryElement("foobar"));
        assertEquals("a%26b", Utf8UrlEncoder.encodeQueryElement("a&b"));
        assertEquals("a%2Bb", Utf8UrlEncoder.encodeQueryElement("a+b"));
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testPercentageEncoding() {
        assertEquals("foobar", Utf8UrlEncoder.percentEncodeQueryElement("foobar"));
        assertEquals("foo%2Abar", Utf8UrlEncoder.percentEncodeQueryElement("foo*bar"));
        assertEquals("foo~b_ar", Utf8UrlEncoder.percentEncodeQueryElement("foo~b_ar"));
    }
}
