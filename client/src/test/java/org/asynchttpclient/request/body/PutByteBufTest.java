/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
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
package org.asynchttpclient.request.body;

import io.github.artsok.RepeatedIfExceptionsTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class PutByteBufTest extends AbstractBasicTest {

    private void put(String message) throws Exception {
        ByteBuf byteBuf = Unpooled.wrappedBuffer(message.getBytes());
        try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(Duration.ofSeconds(2)))) {
            Response response = client.preparePut(getTargetUrl()).setBody(byteBuf).execute().get();
            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getResponseBody(), message);
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testPutSmallBody() throws Exception {
        put("Hello Test");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testPutBigBody() throws Exception {
        byte[] array = new byte[2048];
        Arrays.fill(array, (byte) 97);
        String longString = new String(array, StandardCharsets.UTF_8);

        put(longString);
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new AbstractHandler() {

            @Override
            public void handle(String s, Request request, HttpServletRequest httpRequest, HttpServletResponse response) throws IOException {
                int size = 1024;
                if (request.getContentLength() > 0) {
                    size = request.getContentLength();
                }
                byte[] bytes = new byte[size];
                if (bytes.length > 0) {
                    final int read = request.getInputStream().read(bytes);
                    response.getOutputStream().write(bytes, 0, read);
                }

                response.setStatus(200);
                response.getOutputStream().flush();
            }
        };
    }
}
