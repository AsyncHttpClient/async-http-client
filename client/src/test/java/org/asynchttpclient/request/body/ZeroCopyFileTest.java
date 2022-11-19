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

import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.BasicHttpsTest;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.test.TestUtils.SIMPLE_TEXT_FILE;
import static org.asynchttpclient.test.TestUtils.SIMPLE_TEXT_FILE_STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zero copy test which use FileChannel.transfer under the hood . The same SSL test is also covered in {@link BasicHttpsTest}
 */
public class ZeroCopyFileTest extends AbstractBasicTest {

    @Test
    public void zeroCopyPostTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            final AtomicBoolean headerSent = new AtomicBoolean(false);
            final AtomicBoolean operationCompleted = new AtomicBoolean(false);

            Response resp = client.preparePost("http://localhost:" + port1 + '/').setBody(SIMPLE_TEXT_FILE).execute(new AsyncCompletionHandler<Response>() {

                @Override
                public State onHeadersWritten() {
                    headerSent.set(true);
                    return State.CONTINUE;
                }

                @Override
                public State onContentWritten() {
                    operationCompleted.set(true);
                    return State.CONTINUE;
                }

                @Override
                public Response onCompleted(Response response) {
                    return response;
                }
            }).get();

            assertNotNull(resp);
            assertEquals(HttpServletResponse.SC_OK, resp.getStatusCode());
            assertEquals(SIMPLE_TEXT_FILE_STRING, resp.getResponseBody());
            assertTrue(operationCompleted.get());
            assertTrue(headerSent.get());
        }
    }

    @Test
    public void zeroCopyPutTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            Future<Response> f = client.preparePut("http://localhost:" + port1 + '/').setBody(SIMPLE_TEXT_FILE).execute();
            Response resp = f.get();
            assertNotNull(resp);
            assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
            assertEquals(resp.getResponseBody(), SIMPLE_TEXT_FILE_STRING);
        }
    }

    public static AbstractHandler configureHandler() throws Exception {
        return new ZeroCopyHandler();
    }

    @Test
    public void zeroCopyFileTest() throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir") + File.separator + "zeroCopy.txt");
        tmp.deleteOnExit();
        try (AsyncHttpClient client = asyncHttpClient()) {
            try (OutputStream stream = Files.newOutputStream(tmp.toPath())) {
                Response resp = client.preparePost("http://localhost:" + port1 + '/').setBody(SIMPLE_TEXT_FILE).execute(new AsyncHandler<Response>() {

                    @Override
                    public void onThrowable(Throwable t) {
                    }

                    @Override
                    public State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
                        stream.write(bodyPart.getBodyPartBytes());
                        return State.CONTINUE;
                    }

                    @Override
                    public State onStatusReceived(HttpResponseStatus responseStatus) {
                        return State.CONTINUE;
                    }

                    @Override
                    public State onHeadersReceived(HttpHeaders headers) {
                        return State.CONTINUE;
                    }

                    @Override
                    public Response onCompleted() {
                        return null;
                    }
                }).get();

                assertNull(resp);
                assertEquals(SIMPLE_TEXT_FILE.length(), tmp.length());
            }
        }
    }

    @Test
    public void zeroCopyFileWithBodyManipulationTest() throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir") + File.separator + "zeroCopy.txt");
        tmp.deleteOnExit();
        try (AsyncHttpClient client = asyncHttpClient()) {
            try (OutputStream stream = Files.newOutputStream(tmp.toPath())) {
                Response resp = client.preparePost("http://localhost:" + port1 + '/').setBody(SIMPLE_TEXT_FILE).execute(new AsyncHandler<Response>() {

                    @Override
                    public void onThrowable(Throwable t) {
                    }

                    @Override
                    public State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
                        stream.write(bodyPart.getBodyPartBytes());

                        if (bodyPart.getBodyPartBytes().length == 0) {
                            return State.ABORT;
                        }

                        return State.CONTINUE;
                    }

                    @Override
                    public State onStatusReceived(HttpResponseStatus responseStatus) {
                        return State.CONTINUE;
                    }

                    @Override
                    public State onHeadersReceived(HttpHeaders headers) {
                        return State.CONTINUE;
                    }

                    @Override
                    public Response onCompleted() {
                        return null;
                    }
                }).get();
                assertNull(resp);
                assertEquals(SIMPLE_TEXT_FILE.length(), tmp.length());
            }
        }
    }

    private static class ZeroCopyHandler extends AbstractHandler {

        @Override
        public void handle(String s, Request r, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException {

            int size = 10 * 1024;
            if (httpRequest.getContentLength() > 0) {
                size = httpRequest.getContentLength();
            }
            byte[] bytes = new byte[size];
            if (bytes.length > 0) {
                httpRequest.getInputStream().read(bytes);
                httpResponse.getOutputStream().write(bytes);
            }

            httpResponse.setStatus(200);
            httpResponse.getOutputStream().flush();
        }
    }
}
