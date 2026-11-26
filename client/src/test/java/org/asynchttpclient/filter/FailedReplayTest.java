/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.addHttpsConnector;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link FailedReplayHttp2Test} over HTTP/1.1, through TLS: over plain HTTP, a filter only replays once the
 * connection has closed.
 */
public class FailedReplayTest extends AbstractBasicTest {

    // The handler deletes it once it has read the request body, before it answers with a 307.
    private volatile File body;

    @Override
    @BeforeAll
    public void setUpGlobal() throws Exception {
        server = new Server();
        ServerConnector https = addHttpsConnector(server);
        server.setHandler(configureHandler());
        server.start();
        port1 = https.getLocalPort();
    }

    @Override
    @AfterAll
    public void tearDownGlobal() throws Exception {
        super.tearDownGlobal();
    }

    @AfterEach
    public void deleteBody() throws IOException {
        if (body != null) {
            Files.deleteIfExists(body.toPath());
        }
    }

    @Override
    public AbstractHandler configureHandler() {
        return new AbstractHandler() {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
                baseRequest.setHandled(true);
                request.getInputStream().readAllBytes();
                if ("/redirect".equals(target)) {
                    Files.delete(body.toPath());
                    response.setStatus(307);
                    response.setHeader("Location", "/target");
                } else {
                    response.setStatus(200);
                }
            }
        };
    }

    @Test
    public void aReplayThatCannotBeBuiltFailsTheRequest() throws Exception {
        body = File.createTempFile("failed-replay", ".txt");
        Files.write(body.toPath(), "body".getBytes(US_ASCII));
        AtomicInteger replays = new AtomicInteger();
        IOExceptionFilter replayOnce = new IOExceptionFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                return replays.getAndIncrement() == 0
                        ? new FilterContext.FilterContextBuilder<>(ctx).replayRequest(true).build()
                        : ctx;
            }
        };

        // One connection: the request sent from onThrowable needs the permit of the one the replay came from.
        try (AsyncHttpClient client = asyncHttpClient(config()
                .setUseInsecureTrustManager(true)
                .setFollowRedirect(true)
                .setMaxConnections(1)
                .addIOExceptionFilter(replayOnce))) {
            String base = "https://localhost:" + port1;
            CompletableFuture<ListenableFuture<Response>> next = new CompletableFuture<>();
            ListenableFuture<Response> failed = client.preparePost(base + "/redirect").setBody(body)
                    .execute(new AsyncCompletionHandlerBase() {
                        @Override
                        public void onThrowable(Throwable t) {
                            next.complete(client.prepareGet(base + "/ok").execute());
                        }
                    });
            ExecutionException e = assertThrows(ExecutionException.class, () -> failed.get(10, TimeUnit.SECONDS));

            assertInstanceOf(IllegalArgumentException.class, e.getCause());
            assertEquals(1, replays.get());
            assertEquals(200, next.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS).getStatusCode());
        }
    }
}
