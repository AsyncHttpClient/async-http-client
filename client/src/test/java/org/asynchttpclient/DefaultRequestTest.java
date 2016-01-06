/*
 * Copyright (c) 2013 Sonatype, Inc. All rights reserved.
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
package org.asynchttpclient;

import org.testng.Assert;
import org.testng.annotations.Test;

public class DefaultRequestTest {

    @Test
    public void testByteData() {

        byte[] actualByteData = "testData".getBytes();

        DefaultRequest request = new DefaultRequest(null, null, null, null, null, null, actualByteData, null, null, null, null, null, null, null,
                null, 0, null, null, null, false, 0, 0, null, null, null);

        /*
         * Test references are different
         */
        Assert.assertTrue(actualByteData != request.getByteData());

        /*
         * Test array contents are same
         */
        Assert.assertEquals(actualByteData, request.getByteData());
    }
}
