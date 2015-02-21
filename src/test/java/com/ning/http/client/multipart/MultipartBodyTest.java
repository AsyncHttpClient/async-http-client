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
package com.ning.http.client.multipart;

import static java.nio.charset.StandardCharsets.*;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.ning.http.client.Body;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.multipart.ByteArrayPart;
import com.ning.http.client.multipart.FilePart;
import com.ning.http.client.multipart.Part;
import com.ning.http.client.multipart.StringPart;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class MultipartBodyTest {

    @Test(groups = "fast")
    public void testBasics() {
        final List<Part> parts = new ArrayList<>();

        // add a file
        final File testFile = getTestfile();
        parts.add(new FilePart("filePart", testFile));

        // add a byte array
        parts.add(new ByteArrayPart("baPart", "testMultiPart".getBytes(UTF_8), "application/test", UTF_8, "fileName"));

        // add a string
        parts.add(new StringPart("stringPart", "testString"));

        compareContentLength(parts);
    }

    private static File getTestfile() {
        final ClassLoader cl = MultipartBodyTest.class.getClassLoader();
        final URL url = cl.getResource("textfile.txt");
        Assert.assertNotNull(url);
        File file = null;
        try {
            file = new File(url.toURI());
        } catch (URISyntaxException use) {
            Assert.fail("uri syntax error");
        }
        return file;
    }

    private static void compareContentLength(final List<Part> parts) {
        Assert.assertNotNull(parts);
        // get expected values
        final Body multipartBody = MultipartUtils.newMultipartBody(parts, new FluentCaseInsensitiveStringsMap());
        final long expectedContentLength = multipartBody.getContentLength();
        try {
            final ByteBuffer buffer = ByteBuffer.allocate(8192);
            boolean last = false;
            long totalBytes = 0;
            while (!last) {
                long readBytes = 0;
                try {
                    readBytes = multipartBody.read(buffer);
                } catch (IOException ie) {
                    Assert.fail("read failure");
                }
                if (readBytes >= 0) {
                    totalBytes += readBytes;
                } else {
                    last = true;
                }
                buffer.clear();
            }
            Assert.assertEquals(totalBytes, expectedContentLength);
        } finally {
            try {
                multipartBody.close();
            } catch (IOException ignore) {
            }
        }
    }
}
