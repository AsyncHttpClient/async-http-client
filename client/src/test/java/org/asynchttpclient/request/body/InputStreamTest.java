/*
 * Copyright 2010 Ning, Inc.
 *
 * This program is licensed to you under the Apache License, version 2.0
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
package org.asynchttpclient.request.body;

import io.github.artsok.RepeatedIfExceptionsTest;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class InputStreamTest extends AbstractBasicTest {

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new InputStreamHandler();
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void testInvalidInputStream() throws Exception {

        try (AsyncHttpClient client = asyncHttpClient()) {
            HttpHeaders httpHeaders = new DefaultHttpHeaders().add(CONTENT_TYPE, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED);

            InputStream inputStream = new InputStream() {

                int readAllowed;

                @Override
                public int available() {
                    return 1; // Fake
                }

                @Override
                public int read() {
                    int fakeCount = readAllowed++;
                    if (fakeCount == 0) {
                        return 'a';
                    } else if (fakeCount == 1) {
                        return 'b';
                    } else if (fakeCount == 2) {
                        return 'c';
                    } else {
                        return -1;
                    }
                }
            };

            Response resp = client.preparePost(getTargetUrl()).setHeaders(httpHeaders).setBody(inputStream).execute().get();
            assertNotNull(resp);
            // TODO: 18-11-2022 Revisit
            assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, resp.getStatusCode());
//            assertEquals(resp.getHeader("X-Param"), "abc");
        }
    }

    private static class InputStreamHandler extends AbstractHandler {
        @Override
        public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            if ("POST".equalsIgnoreCase(request.getMethod())) {
                byte[] bytes = new byte[3];
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int read = 0;
                while (read > -1) {
                    read = request.getInputStream().read(bytes);
                    if (read > 0) {
                        bos.write(bytes, 0, read);
                    }
                }

                response.setStatus(HttpServletResponse.SC_OK);
                response.addHeader("X-Param", bos.toString());
            } else { // this handler is to handle POST request
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
            }
            response.getOutputStream().flush();
            response.getOutputStream().close();
        }
    }
}
