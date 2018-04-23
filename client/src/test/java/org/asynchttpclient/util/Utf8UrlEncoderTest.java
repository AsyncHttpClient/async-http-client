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

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class Utf8UrlEncoderTest {
  @Test
  public void testBasics() {
    assertEquals(Utf8UrlEncoder.encodeQueryElement("foobar"), "foobar");
    assertEquals(Utf8UrlEncoder.encodeQueryElement("a&b"), "a%26b");
    assertEquals(Utf8UrlEncoder.encodeQueryElement("a+b"), "a%2Bb");
  }

  @Test
  public void testPercentageEncoding() {
    assertEquals(Utf8UrlEncoder.percentEncodeQueryElement("foobar"), "foobar");
    assertEquals(Utf8UrlEncoder.percentEncodeQueryElement("foo*bar"), "foo%2Abar");
    assertEquals(Utf8UrlEncoder.percentEncodeQueryElement("foo~b_ar"), "foo~b_ar");
  }
}
