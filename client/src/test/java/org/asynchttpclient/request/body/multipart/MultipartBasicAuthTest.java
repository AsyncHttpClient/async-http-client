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

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderValues.APPLICATION_OCTET_STREAM;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.test.TestUtils.*;
import static org.testng.Assert.assertEquals;

import java.io.File;

import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.BasicAuthTest;
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

    @Test(groups = "standalone", enabled = false)
    public void testNoRealm() throws Exception {
        File file = createTempFile(1024 * 1024);

        try (AsyncHttpClient client = asyncHttpClient()) {
            for (int i = 0; i < 20; i++) {
                Response response = client.preparePut(getTargetUrl())//
                        .addBodyPart(new FilePart("test", file, APPLICATION_OCTET_STREAM.toString(), UTF_8)).execute().get();
                assertEquals(response.getStatusCode(), 401);
            }
        }
    }

    @Test(groups = "standalone", enabled = false)
    public void testAuthorizedRealm() throws Exception {
        File file = createTempFile(1024 * 1024);

        try (AsyncHttpClient client = asyncHttpClient()) {
            for (int i = 0; i < 20; i++) {
                Response response = client.preparePut(getTargetUrl())//
                        .setRealm(basicAuthRealm(USER, ADMIN).build())//
                        .addBodyPart(new FilePart("test", file, APPLICATION_OCTET_STREAM.toString(), UTF_8)).execute().get();
                assertEquals(response.getStatusCode(), 200);
                assertEquals(response.getResponseBodyAsBytes().length, Integer.valueOf(response.getHeader("X-" + CONTENT_LENGTH)).intValue());
            }
        }
    }
}
