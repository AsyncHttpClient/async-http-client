/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.request.body.multipart;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpHeaderValues.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.test.TestUtils.*;
import static org.testng.Assert.*;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.BasicAuthTest;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class MultipartBasicAuthTest extends AbstractBasicTest {

    @BeforeClass(alwaysRun = true)
    @Override
    public void setUpGlobal() throws Exception {
        server = new Server();
        ServerConnector connector1 = addHttpConnector(server);
        addBasicAuthHandler(server, configureHandler());
        server.start();
        port1 = connector1.getLocalPort();
        logger.info("Local HTTP server started successfully");
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new BasicAuthTest.SimpleHandler();
    }

    private void expectBrokenPipe(Function<BoundRequestBuilder, BoundRequestBuilder> f) throws Exception {
        File file = createTempFile(1024 * 1024);

        Throwable cause = null;
        try (AsyncHttpClient client = asyncHttpClient()) {
            try {
                for (int i = 0; i < 20 && cause == null; i++) {
                    f.apply(client.preparePut(getTargetUrl())//
                            .addBodyPart(new FilePart("test", file, APPLICATION_OCTET_STREAM.toString(), UTF_8)))//
                            .execute().get();
                }
            } catch (ExecutionException e) {
                cause = e.getCause();
            }
        }

        assertTrue(cause instanceof IOException, "Expected an IOException");
        assertEquals(cause.getMessage(), "Broken pipe");
    }

    @Test(groups = "standalone")
    public void noRealmCausesServerToCloseSocket() throws Exception {
        expectBrokenPipe(rb -> rb);
    }

    @Test(groups = "standalone")
    public void unauthorizedNonPreemptiveRealmCausesServerToCloseSocket() throws Exception {
        expectBrokenPipe(rb -> rb.setRealm(basicAuthRealm(USER, ADMIN)));
    }

    private void expectSuccess(Function<BoundRequestBuilder, BoundRequestBuilder> f) throws Exception {
        File file = createTempFile(1024 * 1024);

        try (AsyncHttpClient client = asyncHttpClient()) {
            for (int i = 0; i < 20; i++) {
                Response response = f.apply(client.preparePut(getTargetUrl())//
                        .addBodyPart(new FilePart("test", file, APPLICATION_OCTET_STREAM.toString(), UTF_8)))//
                        .execute().get();
                assertEquals(response.getStatusCode(), 200);
                assertEquals(response.getResponseBodyAsBytes().length, Integer.valueOf(response.getHeader("X-" + CONTENT_LENGTH)).intValue());
            }
        }
    }

    @Test(groups = "standalone")
    public void authorizedPreemptiveRealmWorks() throws Exception {
        expectSuccess(rb -> rb.setRealm(basicAuthRealm(USER, ADMIN).setUsePreemptiveAuth(true)));
    }

    @Test(groups = "standalone")
    public void authorizedNonPreemptiveRealmWorksWithExpectContinue() throws Exception {
        expectSuccess(rb -> rb.setRealm(basicAuthRealm(USER, ADMIN)).setHeader(EXPECT, CONTINUE));
    }
}
