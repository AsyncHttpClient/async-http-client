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
import io.netty.util.internal.SocketUtils;
import org.asynchttpclient.test.TestUtils.AsyncCompletionHandlerAdapter;
import org.asynchttpclient.testserver.HttpServer;
import org.asynchttpclient.testserver.HttpTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.test.TestUtils.TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class CustomRemoteAddressTest extends HttpTest {

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

    @RepeatedIfExceptionsTest(repeats = 10)
    public void getRootUrlWithCustomRemoteAddress() throws Throwable {
        withClient().run(client ->
                withServer(server).run(server -> {
                    String url = server.getHttpUrl();
                    server.enqueueOk();
                    RequestBuilder request = get(url).setAddress(SocketUtils.addressByName("localhost"));
                    Response response = client.executeRequest(request, new AsyncCompletionHandlerAdapter()).get(TIMEOUT, SECONDS);
                    assertEquals(response.getStatusCode(), 200);
                }));
    }
}
