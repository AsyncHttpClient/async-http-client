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

import io.netty.util.internal.SocketUtils;
import org.asynchttpclient.test.TestUtils.AsyncCompletionHandlerAdapter;
import org.asynchttpclient.testserver.HttpServer;
import org.asynchttpclient.testserver.HttpTest;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.test.TestUtils.TIMEOUT;
import static org.testng.Assert.assertEquals;

public class CustomRemoteAddressTest extends HttpTest {

  private static HttpServer server;

  @BeforeClass
  public static void start() throws Throwable {
    server = new HttpServer();
    server.start();
  }

  @AfterClass
  public static void stop() throws Throwable {
    server.close();
  }

  @Test
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
