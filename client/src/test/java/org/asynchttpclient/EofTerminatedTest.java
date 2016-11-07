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
package org.asynchttpclient;

import static io.netty.handler.codec.http.HttpHeaders.Names.*;
import static io.netty.handler.codec.http.HttpHeaders.Values.*;
import static org.asynchttpclient.Dsl.*;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.testng.annotations.Test;

public class EofTerminatedTest extends AbstractBasicTest {

    private static class StreamHandler extends AbstractHandler {
        @Override
        public void handle(String pathInContext, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException {
            request.getResponse().getHttpOutput().sendContent(EofTerminatedTest.class.getClassLoader().getResourceAsStream("SimpleTextFile.txt"));
        }
    }

    protected String getTargetUrl() {
        return String.format("http://localhost:%d/", port1);
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setHandler(new StreamHandler());
        return gzipHandler;
    }

    @Test(enabled = false)
    public void testEolTerminatedResponse() throws Exception {
        try (AsyncHttpClient ahc = asyncHttpClient(config().setMaxRequestRetry(0))) {
            ahc.executeRequest(ahc.prepareGet(getTargetUrl()).setHeader(ACCEPT_ENCODING, GZIP_DEFLATE).setHeader(CONNECTION, CLOSE).build()).get();
        }
    }
}
