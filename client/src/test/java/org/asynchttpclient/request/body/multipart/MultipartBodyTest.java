/*
 *    Copyright (c) 2016-2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.request.body.multipart;

import io.github.artsok.RepeatedIfExceptionsTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import org.asynchttpclient.request.body.Body.BodyState;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class MultipartBodyTest {

    private static final List<Part> PARTS = new ArrayList<>();
    private static final long MAX_MULTIPART_CONTENT_LENGTH_ESTIMATE;

    static {
        try {
            PARTS.add(new FilePart("filePart", getTestfile()));
        } catch (URISyntaxException e) {
            throw new ExceptionInInitializerError(e);
        }
        PARTS.add(new ByteArrayPart("baPart", "testMultiPart".getBytes(UTF_8), "application/test", UTF_8, "fileName"));
        PARTS.add(new StringPart("stringPart", "testString"));
    }

    static {
        try (MultipartBody dummyBody = buildMultipart()) {
            // separator is random
            MAX_MULTIPART_CONTENT_LENGTH_ESTIMATE = dummyBody.getContentLength() + 100;
        }
    }

    private static File getTestfile() throws URISyntaxException {
        final ClassLoader cl = MultipartBodyTest.class.getClassLoader();
        final URL url = cl.getResource("textfile.txt");
        assertNotNull(url);
        return new File(url.toURI());
    }

    private static MultipartBody buildMultipart() {
        List<Part> parts = new ArrayList<>(PARTS);
        try {
            File testFile = getTestfile();
            InputStream inputStream = new BufferedInputStream(new FileInputStream(testFile));
            parts.add(new InputStreamPart("isPart", inputStream, testFile.getName(), testFile.length()));
        } catch (URISyntaxException | FileNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
        return MultipartUtils.newMultipartBody(parts, EmptyHttpHeaders.INSTANCE);
    }

    private static long transferWithCopy(MultipartBody multipartBody, int bufferSize) throws IOException {
        long transferred = 0;
        final ByteBuf buffer = Unpooled.buffer(bufferSize);
        try {
            while (multipartBody.transferTo(buffer) != BodyState.STOP) {
                transferred += buffer.readableBytes();
                buffer.clear();
            }
            return transferred;
        } finally {
            buffer.release();
        }
    }

    private static long transferZeroCopy(MultipartBody multipartBody, int bufferSize) throws IOException {

        final ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
        final AtomicLong transferred = new AtomicLong();

        WritableByteChannel mockChannel = new WritableByteChannel() {
            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void close() {
            }

            @Override
            public int write(ByteBuffer src) {
                int written = src.remaining();
                transferred.set(transferred.get() + written);
                src.position(src.limit());
                return written;
            }
        };

        while (transferred.get() < multipartBody.getContentLength()) {
            multipartBody.transferTo(mockChannel);
            buffer.clear();
        }
        return transferred.get();
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void transferWithCopy() throws Exception {
        for (int bufferLength = 1; bufferLength < MAX_MULTIPART_CONTENT_LENGTH_ESTIMATE + 1; bufferLength++) {
            try (MultipartBody multipartBody = buildMultipart()) {
                long transferred = transferWithCopy(multipartBody, bufferLength);
                assertEquals(multipartBody.getContentLength(), transferred);
            }
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void transferZeroCopy() throws Exception {
        for (int bufferLength = 1; bufferLength < MAX_MULTIPART_CONTENT_LENGTH_ESTIMATE + 1; bufferLength++) {
            try (MultipartBody multipartBody = buildMultipart()) {
                long transferred = transferZeroCopy(multipartBody, bufferLength);
                assertEquals(multipartBody.getContentLength(), transferred);
            }
        }
    }
}
