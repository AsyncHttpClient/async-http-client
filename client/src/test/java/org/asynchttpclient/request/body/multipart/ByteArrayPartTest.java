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
package org.asynchttpclient.request.body.multipart;

import org.testng.annotations.Test;
import org.testng.Assert;

public class ByteArrayPartTest {
    
    @Test
    public void testByteArrayReference() {
        byte[] originalByteArray = "abcd".getBytes();
        ByteArrayPart part = new ByteArrayPart("Test", originalByteArray);
        
        /*
         * Test references are different
         */
        Assert.assertTrue(originalByteArray != part.getBytes());
        
        /*
         * Test arrays have same contents
         */
        Assert.assertEquals(originalByteArray, part.getBytes());
    }
}
