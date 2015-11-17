/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.util;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.Test;

public class TestUTF8UrlCodec {
    @Test(groups = "standalone")
    public void testBasics() {
        assertEquals(Utf8UrlEncoder.encodeQueryElement("foobar"), "foobar");
        assertEquals(Utf8UrlEncoder.encodeQueryElement("a&b"), "a%26b");
        assertEquals(Utf8UrlEncoder.encodeQueryElement("a+b"), "a%2Bb");
    }
}
