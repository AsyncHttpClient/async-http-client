/*
 *    Copyright (c) 2017-2023 AsyncHttpClient Project. All rights reserved.
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
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.BasicAuthTest;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.BeforeEach;

import java.io.File;
import java.util.function.Function;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.EXPECT;
import static io.netty.handler.codec.http.HttpHeaderValues.APPLICATION_OCTET_STREAM;
import static io.netty.handler.codec.http.HttpHeaderValues.CONTINUE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.basicAuthRealm;
import static org.asynchttpclient.test.TestUtils.ADMIN;
import static org.asynchttpclient.test.TestUtils.USER;
import static org.asynchttpclient.test.TestUtils.addBasicAuthHandler;
import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.asynchttpclient.test.TestUtils.createTempFile;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MultipartBasicAuthTest extends AbstractBasicTest {

    @Override
    @BeforeEach
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

    private void expectHttpResponse(Function<BoundRequestBuilder, BoundRequestBuilder> f, int expectedResponseCode) throws Throwable {
        File file = createTempFile(1024 * 1024);

        try (AsyncHttpClient client = asyncHttpClient()) {
            Response response = f.apply(client.preparePut(getTargetUrl()).addBodyPart(new FilePart("test", file, APPLICATION_OCTET_STREAM.toString(), UTF_8)))
                    .execute()
                    .get();
            assertEquals(expectedResponseCode, response.getStatusCode());
        }
    }

    @RepeatedIfExceptionsTest(repeats = 3)
    public void noRealmCausesServerToCloseSocket() throws Throwable {
        expectHttpResponse(rb -> rb, 401);
    }

    @RepeatedIfExceptionsTest(repeats = 3)
    public void unauthorizedNonPreemptiveRealmCausesServerToCloseSocket() throws Throwable {
        expectHttpResponse(rb -> rb.setRealm(basicAuthRealm(USER, "NOT-ADMIN")), 401);
    }

    private void expectSuccess(Function<BoundRequestBuilder, BoundRequestBuilder> f) throws Exception {
        File file = createTempFile(1024 * 1024);

        try (AsyncHttpClient client = asyncHttpClient()) {
            for (int i = 0; i < 20; i++) {
                Response response = f.apply(client.preparePut(getTargetUrl())
                                .addBodyPart(new FilePart("test", file, APPLICATION_OCTET_STREAM.toString(), UTF_8)))
                        .execute().get();
                assertEquals(response.getStatusCode(), 200);
                assertEquals(response.getResponseBodyAsBytes().length, Integer.valueOf(response.getHeader("X-" + CONTENT_LENGTH)).intValue());
            }
        }
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void authorizedPreemptiveRealmWorks() throws Exception {
        expectSuccess(rb -> rb.setRealm(basicAuthRealm(USER, ADMIN).setUsePreemptiveAuth(true)));
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void authorizedNonPreemptiveRealmWorksWithExpectContinue() throws Exception {
        expectSuccess(rb -> rb.setRealm(basicAuthRealm(USER, ADMIN)).setHeader(EXPECT, CONTINUE));
    }
}
