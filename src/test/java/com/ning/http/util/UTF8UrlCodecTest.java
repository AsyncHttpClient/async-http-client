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
package com.ning.http.util;

import org.testng.Assert;
import org.testng.annotations.Test;

public class UTF8UrlCodecTest {

    @Test(groups = "fast")
    public void testBasics() {
        Assert.assertEquals(UTF8UrlEncoder.encodeQueryElement("foobar"), "foobar");
        Assert.assertEquals(UTF8UrlEncoder.encodeQueryElement("a&b"), "a%26b");
        Assert.assertEquals(UTF8UrlEncoder.encodeQueryElement("a+b"), "a%2Bb");
    }

    @Test(groups = "fast")
    public void testNonBmp() {
        // Plane 1
        Assert.assertEquals(UTF8UrlEncoder.encodeQueryElement("\uD83D\uDCA9"), "%F0%9F%92%A9");
        // Plane 2
        Assert.assertEquals(UTF8UrlEncoder.encodeQueryElement("\ud84c\uddc8 \ud84f\udfef"), "%F0%A3%87%88%20%F0%A3%BF%AF");
        // Plane 15
        Assert.assertEquals(UTF8UrlEncoder.encodeQueryElement("\udb80\udc01"), "%F3%B0%80%81");
    }

    @Test(groups = "fast")
    public void testDecodeBasics() {
        Assert.assertEquals(UTF8UrlDecoder.decode("foobar").toString(), "foobar");
        Assert.assertEquals(UTF8UrlDecoder.decode("a&b").toString(), "a&b");
        Assert.assertEquals(UTF8UrlDecoder.decode("a+b").toString(), "a b");

        Assert.assertEquals(UTF8UrlDecoder.decode("+").toString(), " ");
        Assert.assertEquals(UTF8UrlDecoder.decode("%20").toString(), " ");
        Assert.assertEquals(UTF8UrlDecoder.decode("%25").toString(), "%");

        Assert.assertEquals(UTF8UrlDecoder.decode("+%20x").toString(), "  x");
    }

    @Test(groups = "fast")
    public void testDecodeTooShort() {
        try {
            UTF8UrlDecoder.decode("%2");
            Assert.assertTrue(false, "No exception thrown on illegal encoding length");
        } catch (IllegalArgumentException ex) {
            Assert.assertEquals(ex.getMessage(), "UTF8UrlDecoder: Incomplete trailing escape (%) pattern");
        } catch (StringIndexOutOfBoundsException ex) {
            Assert.assertTrue(false, "String Index Out of Bound thrown, but should be IllegalArgument");
        }
    }
}
