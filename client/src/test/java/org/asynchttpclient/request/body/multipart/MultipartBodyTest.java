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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.testng.Assert.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaders;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.asynchttpclient.request.body.Body.BodyState;
import org.testng.annotations.Test;

public class MultipartBodyTest {

    @Test(groups = "standalone")
    public void testBasics() throws Exception {
        final List<Part> parts = new ArrayList<>();

        // add a file
        final File testFile = getTestfile();
        System.err.println(testFile.length());
        parts.add(new FilePart("filePart", testFile));

        // add a byte array
        parts.add(new ByteArrayPart("baPart", "testMultiPart".getBytes(UTF_8), "application/test", UTF_8, "fileName"));

        // add a string
        parts.add(new StringPart("stringPart", "testString"));

        compareContentLength(parts);
    }

    private static File getTestfile() throws URISyntaxException {
        final ClassLoader cl = MultipartBodyTest.class.getClassLoader();
        final URL url = cl.getResource("textfile.txt");
        assertNotNull(url);
        return new File(url.toURI());
    }

    private static void compareContentLength(final List<Part> parts) throws IOException {
        assertNotNull(parts);
        // get expected values
        final MultipartBody multipartBody = MultipartUtils.newMultipartBody(parts, HttpHeaders.EMPTY_HEADERS);
        final long expectedContentLength = multipartBody.getContentLength();
        try {
            final ByteBuf buffer = Unpooled.buffer(8192);
            while (multipartBody.transferTo(buffer) != BodyState.STOP) {
            }
            assertEquals(buffer.readableBytes(), expectedContentLength);
        } finally {
            IOUtils.closeQuietly(multipartBody);
        }
    }
}
