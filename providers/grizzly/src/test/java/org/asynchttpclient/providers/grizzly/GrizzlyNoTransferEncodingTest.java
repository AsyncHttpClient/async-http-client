/*
 * Copyright (c) 2014 Sonatype, Inc. All rights reserved.
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

package org.asynchttpclient.providers.grizzly;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import static org.glassfish.grizzly.http.server.NetworkListener.DEFAULT_NETWORK_HOST;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class GrizzlyNoTransferEncodingTest {
    private static final String TEST_MESSAGE = "Hello World!";
    
    private HttpServer server;
    private int port;
    // ------------------------------------------------------------------- Setup


    @BeforeMethod
    public void setup() throws Exception {
        server = new HttpServer();
        final NetworkListener listener =
                new NetworkListener("server",
                                    DEFAULT_NETWORK_HOST,
                                    0);
        // disable chunking
        listener.setChunkingEnabled(false);
        server.addListener(listener);
        server.getServerConfiguration().addHttpHandler(
                new HttpHandler() {

                    @Override
                    public void service(final Request request,
                            final Response response) throws Exception {
                        response.setContentType("plain/text;charset=\"utf-8\"");
                        // flush to make sure content-length will be missed
                        response.flush();
                        
                        response.getWriter().write(TEST_MESSAGE);
                    }
                }, "/test");
        
        server.start();
        
        port = listener.getPort();
    }


    // --------------------------------------------------------------- Tear Down


    @AfterMethod
    public void tearDown() {
        server.shutdownNow();
        server = null;
    }


    // ------------------------------------------------------------ Test Methods


    @Test
    public void testNoTransferEncoding() throws Exception {
        String url = "http://localhost:" + port + "/test";

        AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder()
            .setFollowRedirect(false)
            .setConnectionTimeout(15000)
            .setRequestTimeout(15000)
            .setAllowPoolingConnections(false)
            .setDisableUrlEncodingForBoundRequests(true)
            .setIOThreadMultiplier(2) // 2 is default
            .build();

        AsyncHttpClient client = new DefaultAsyncHttpClient(
                new GrizzlyAsyncHttpProvider(config), config);

        try {
            Future<org.asynchttpclient.Response> f = client.prepareGet(url).execute();
            org.asynchttpclient.Response r = f.get(10, TimeUnit.SECONDS);
            Assert.assertEquals(TEST_MESSAGE, r.getResponseBody());
        } finally {
            client.close();
        }
    }
}
