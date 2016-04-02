/*
 * Copyright (c) 2016 AsyncHttpClient Project. All rights reserved.
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
package com.ning.http.client.multipart;

import static java.nio.charset.StandardCharsets.UTF_8;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.ning.http.client.FluentCaseInsensitiveStringsMap;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class MultipartBodyTest {

    @Test
    public void transferWithCopy() throws IOException {
        try (MultipartBody multipartBody = buildMultipart()) {
            long tranferred = transferWithCopy(multipartBody);
            Assert.assertEquals(tranferred, multipartBody.getContentLength());
        }
    }

    @Test
    public void transferZeroCopy() throws IOException {
        try (MultipartBody multipartBody = buildMultipart()) {
            long tranferred = transferZeroCopy(multipartBody);
            Assert.assertEquals(tranferred, multipartBody.getContentLength());
        }
    }

    private static MultipartBody buildMultipart() {
        List<Part> parts = new ArrayList<>();
        parts.add(new FilePart("filePart", getTestfile()));
        parts.add(new ByteArrayPart("baPart", "testMultiPart".getBytes(UTF_8), "application/test", UTF_8, "fileName"));
        parts.add(new StringPart("stringPart", "testString"));
        return MultipartUtils.newMultipartBody(parts, new FluentCaseInsensitiveStringsMap());
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

    private static long transferWithCopy(MultipartBody multipartBody) throws IOException {

        final ByteBuffer buffer = ByteBuffer.allocate(8192);
        long totalBytes = 0;
        while (true) {
            long readBytes = multipartBody.read(buffer);
            if (readBytes < 0) {
                break;
            }
            buffer.clear();
            totalBytes += readBytes;
        }
        return totalBytes;
    }

    private static long transferZeroCopy(MultipartBody multipartBody) throws IOException {

        final ByteBuffer buffer = ByteBuffer.allocate(8192);
        final AtomicLong transferred = new AtomicLong();

        WritableByteChannel mockChannel = new WritableByteChannel() {
            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void close() throws IOException {
            }

            @Override
            public int write(ByteBuffer src) throws IOException {
                int written = src.remaining();
                transferred.set(transferred.get() + written);
                src.position(src.limit());
                return written;
            }
        };

        while (transferred.get() < multipartBody.getContentLength()) {
            multipartBody.transferTo(0, mockChannel);
            buffer.clear();
        }
        return transferred.get();
    }
}
