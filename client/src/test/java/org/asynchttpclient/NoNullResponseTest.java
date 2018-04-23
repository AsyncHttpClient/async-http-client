/*
 * Copyright 2010-2013 Ning, Inc.
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
 *
 */
package org.asynchttpclient;

import org.testng.annotations.Test;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.testng.Assert.assertNotNull;

public class NoNullResponseTest extends AbstractBasicTest {
  private static final String GOOGLE_HTTPS_URL = "https://www.google.com";

  @Test(groups = "online", invocationCount = 4)
  public void multipleSslRequestsWithDelayAndKeepAlive() throws Exception {

    AsyncHttpClientConfig config = config()
            .setFollowRedirect(true)
            .setKeepAlive(true)
            .setConnectTimeout(10000)
            .setPooledConnectionIdleTimeout(60000)
            .setRequestTimeout(10000)
            .setMaxConnectionsPerHost(-1)
            .setMaxConnections(-1)
            .build();

    try (AsyncHttpClient client = asyncHttpClient(config)) {
      final BoundRequestBuilder builder = client.prepareGet(GOOGLE_HTTPS_URL);
      final Response response1 = builder.execute().get();
      Thread.sleep(4000);
      final Response response2 = builder.execute().get();
      assertNotNull(response1);
      assertNotNull(response2);
    }
  }
}
