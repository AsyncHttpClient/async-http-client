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
package org.asynchttpclient;

import io.github.artsok.RepeatedIfExceptionsTest;
import io.netty.handler.codec.http.HttpHeaderValues;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;

import java.io.IOException;

import static io.netty.handler.codec.http.HttpHeaderNames.ACCEPT_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;

public class EofTerminatedTest extends AbstractBasicTest {

    @Override
    protected String getTargetUrl() {
        return String.format("http://localhost:%d/", port1);
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setHandler(new StreamHandler());
        return gzipHandler;
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testEolTerminatedResponse() throws Exception {
        try (AsyncHttpClient ahc = asyncHttpClient(config().setMaxRequestRetry(0))) {
            ahc.executeRequest(ahc.prepareGet(getTargetUrl()).setHeader(ACCEPT_ENCODING, HttpHeaderValues.GZIP_DEFLATE).setHeader(CONNECTION, HttpHeaderValues.CLOSE).build())
                    .get();
        }
    }

    private static class StreamHandler extends AbstractHandler {
        @Override
        public void handle(String pathInContext, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException {
            request.getResponse().getHttpOutput().sendContent(EofTerminatedTest.class.getClassLoader().getResourceAsStream("SimpleTextFile.txt"));
        }
    }
}
