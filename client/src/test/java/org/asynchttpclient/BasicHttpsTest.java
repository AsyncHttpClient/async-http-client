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
package org.asynchttpclient;

import io.github.artsok.RepeatedIfExceptionsTest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.channel.KeepAliveStrategy;
import org.asynchttpclient.test.EventCollectingHandler;
import org.asynchttpclient.testserver.HttpServer;
import org.asynchttpclient.testserver.HttpTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;

import javax.net.ssl.SSLHandshakeException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.LARGE_IMAGE_FILE;
import static org.asynchttpclient.test.TestUtils.SIMPLE_TEXT_FILE;
import static org.asynchttpclient.test.TestUtils.SIMPLE_TEXT_FILE_STRING;
import static org.asynchttpclient.test.TestUtils.TIMEOUT;
import static org.asynchttpclient.test.TestUtils.createSslEngineFactory;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BasicHttpsTest extends HttpTest {

    private HttpServer server;

    @BeforeEach
    public void start() throws Throwable {
        server = new HttpServer();
        server.start();
    }

    @AfterEach
    public void stop() throws Throwable {
        server.close();
    }

    private String getTargetUrl() {
        return server.getHttpsUrl() + "/foo/bar";
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void postFileOverHttps() throws Throwable {
        logger.debug(">>> postBodyOverHttps");
        withClient(config().setSslEngineFactory(createSslEngineFactory())).run(client ->
                withServer(server).run(server -> {
                    server.enqueueEcho();

                    Response resp = client.preparePost(getTargetUrl()).setBody(SIMPLE_TEXT_FILE).setHeader(CONTENT_TYPE, "text/html").execute().get();
                    assertNotNull(resp);
                    assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
                    assertEquals(resp.getResponseBody(), SIMPLE_TEXT_FILE_STRING);
                }));
        logger.debug("<<< postBodyOverHttps");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void postLargeFileOverHttps() throws Throwable {
        logger.debug(">>> postLargeFileOverHttps");
        withClient(config().setSslEngineFactory(createSslEngineFactory())).run(client ->
                withServer(server).run(server -> {
                    server.enqueueEcho();

                    Response resp = client.preparePost(getTargetUrl()).setBody(LARGE_IMAGE_FILE).setHeader(CONTENT_TYPE, "image/png").execute().get();
                    assertNotNull(resp);
                    assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
                    assertEquals(resp.getResponseBodyAsBytes().length, LARGE_IMAGE_FILE.length());
                }));
        logger.debug("<<< postLargeFileOverHttps");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void multipleSequentialPostRequestsOverHttps() throws Throwable {
        logger.debug(">>> multipleSequentialPostRequestsOverHttps");
        withClient(config().setSslEngineFactory(createSslEngineFactory())).run(client ->
                withServer(server).run(server -> {
                    server.enqueueEcho();
                    server.enqueueEcho();

                    String body = "hello there";
                    Response response = client.preparePost(getTargetUrl()).setBody(body).setHeader(CONTENT_TYPE, "text/html").execute().get(TIMEOUT, SECONDS);
                    assertEquals(response.getResponseBody(), body);

                    response = client.preparePost(getTargetUrl()).setBody(body).setHeader(CONTENT_TYPE, "text/html").execute().get(TIMEOUT, SECONDS);
                    assertEquals(response.getResponseBody(), body);
                }));
        logger.debug("<<< multipleSequentialPostRequestsOverHttps");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void multipleConcurrentPostRequestsOverHttpsWithDisabledKeepAliveStrategy() throws Throwable {
        logger.debug(">>> multipleConcurrentPostRequestsOverHttpsWithDisabledKeepAliveStrategy");

        KeepAliveStrategy keepAliveStrategy = (remoteAddress, ahcRequest, nettyRequest, nettyResponse) -> !ahcRequest.getUri().isSecured();

        withClient(config().setSslEngineFactory(createSslEngineFactory()).setKeepAliveStrategy(keepAliveStrategy)).run(client ->
                withServer(server).run(server -> {
                    server.enqueueEcho();
                    server.enqueueEcho();
                    server.enqueueEcho();

                    String body = "hello there";

                    client.preparePost(getTargetUrl()).setBody(body).setHeader(CONTENT_TYPE, "text/html").execute();
                    client.preparePost(getTargetUrl()).setBody(body).setHeader(CONTENT_TYPE, "text/html").execute();

                    Response response = client.preparePost(getTargetUrl()).setBody(body).setHeader(CONTENT_TYPE, "text/html").execute().get();
                    assertEquals(response.getResponseBody(), body);
                }));

        logger.debug("<<< multipleConcurrentPostRequestsOverHttpsWithDisabledKeepAliveStrategy");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void reconnectAfterFailedCertificationPath() throws Throwable {
        logger.debug(">>> reconnectAfterFailedCertificationPath");

        AtomicBoolean trust = new AtomicBoolean();

        withClient(config().setMaxRequestRetry(0).setSslEngineFactory(createSslEngineFactory(trust))).run(client ->
                withServer(server).run(server -> {
                    server.enqueueEcho();
                    server.enqueueEcho();

                    String body = "hello there";

                    // first request fails because server certificate is rejected
                    Throwable cause = null;
                    try {
                        client.preparePost(getTargetUrl()).setBody(body).setHeader(CONTENT_TYPE, "text/html").execute().get(TIMEOUT, SECONDS);
                    } catch (final ExecutionException e) {
                        cause = e.getCause();
                    }
                    assertNotNull(cause);

                    // second request should succeed
                    trust.set(true);
                    Response response = client.preparePost(getTargetUrl()).setBody(body).setHeader(CONTENT_TYPE, "text/html").execute().get(TIMEOUT, SECONDS);

                    assertEquals(response.getResponseBody(), body);
                }));
        logger.debug("<<< reconnectAfterFailedCertificationPath");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 2000)
    public void failInstantlyIfNotAllowedSelfSignedCertificate() throws Throwable {
        logger.debug(">>> failInstantlyIfNotAllowedSelfSignedCertificate");

        assertThrows(SSLHandshakeException.class, () -> {
            withClient(config().setMaxRequestRetry(0).setRequestTimeout(Duration.ofSeconds(2))).run(client ->
                    withServer(server).run(server -> {
                        try {
                            client.prepareGet(getTargetUrl()).execute().get(TIMEOUT, SECONDS);
                        } catch (ExecutionException e) {
                            throw e.getCause().getCause();
                        }
                    }));
        });
        logger.debug("<<< failInstantlyIfNotAllowedSelfSignedCertificate");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testNormalEventsFired() throws Throwable {
        logger.debug(">>> testNormalEventsFired");

        withClient(config().setSslEngineFactory(createSslEngineFactory())).run(client ->
                withServer(server).run(server -> {
                    EventCollectingHandler handler = new EventCollectingHandler();

                    server.enqueueEcho();
                    client.preparePost(getTargetUrl()).setBody("whatever").execute(handler).get(3, SECONDS);
                    handler.waitForCompletion(3, SECONDS);

                    Object[] expectedEvents = {
                            CONNECTION_POOL_EVENT,
                            HOSTNAME_RESOLUTION_EVENT,
                            HOSTNAME_RESOLUTION_SUCCESS_EVENT,
                            CONNECTION_OPEN_EVENT,
                            CONNECTION_SUCCESS_EVENT,
                            TLS_HANDSHAKE_EVENT,
                            TLS_HANDSHAKE_SUCCESS_EVENT,
                            REQUEST_SEND_EVENT,
                            HEADERS_WRITTEN_EVENT,
                            STATUS_RECEIVED_EVENT,
                            HEADERS_RECEIVED_EVENT,
                            CONNECTION_OFFER_EVENT,
                            COMPLETED_EVENT};

                    assertArrayEquals(handler.firedEvents.toArray(), expectedEvents, "Got " + Arrays.toString(handler.firedEvents.toArray()));
                }));
        logger.debug("<<< testNormalEventsFired");
    }
}
