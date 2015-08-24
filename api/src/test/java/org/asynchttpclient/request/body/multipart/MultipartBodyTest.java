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

import static java.nio.charset.StandardCharsets.*;

import org.asynchttpclient.FluentCaseInsensitiveStringsMap;
import org.asynchttpclient.request.body.Body;
import org.asynchttpclient.request.body.Body.State;
import org.asynchttpclient.request.body.multipart.ByteArrayPart;
import org.asynchttpclient.request.body.multipart.FilePart;
import org.asynchttpclient.request.body.multipart.MultipartUtils;
import org.asynchttpclient.request.body.multipart.Part;
import org.asynchttpclient.request.body.multipart.StringPart;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class MultipartBodyTest {

    @Test(groups = "fast")
    public void testBasics() throws IOException {
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

    private static void compareContentLength(final List<Part> parts) throws IOException {
        Assert.assertNotNull(parts);
        // get expected values
        final Body multipartBody = MultipartUtils.newMultipartBody(parts, new FluentCaseInsensitiveStringsMap());
        final long expectedContentLength = multipartBody.getContentLength();
        try {
            final ByteBuffer buffer = ByteBuffer.allocate(8192);
            boolean last = false;
            while (!last) {
                if (multipartBody.read(buffer) == State.Stop) {
                    last = true;
                }
            }
            Assert.assertEquals(buffer.position(), expectedContentLength);
        } finally {
            try {
                multipartBody.close();
            } catch (IOException ignore) {
            }
        }
    }
}
