/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
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
package org.asynchttpclient.netty;

import org.asynchttpclient.*;
import org.testng.annotations.Test;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;

public class TimeToLiveIssue extends AbstractBasicTest {
  @Test(enabled = false, description = "https://github.com/AsyncHttpClient/async-http-client/issues/1113")
  public void testTTLBug() throws Throwable {
    // The purpose of this test is to reproduce two issues:
    // 1) Connections that are rejected by the pool are not closed and eventually use all available sockets.
    // 2) It is possible for a connection to be closed while active by the timer task that checks for expired connections.

    try (AsyncHttpClient client = asyncHttpClient(config().setKeepAlive(true).setConnectionTtl(1).setPooledConnectionIdleTimeout(1))) {

      for (int i = 0; i < 200000; ++i) {
        Request request = new RequestBuilder().setUrl(String.format("http://localhost:%d/", port1)).build();

        Future<Response> future = client.executeRequest(request);
        future.get(5, TimeUnit.SECONDS);

        // This is to give a chance to the timer task that removes expired connection
        // from sometimes winning over poll for the ownership of a connection.
        if (System.currentTimeMillis() % 100 == 0) {
          Thread.sleep(5);
        }
      }
    }
  }
}
