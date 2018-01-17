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
package org.asynchttpclient.request.body;

import org.asynchttpclient.*;
import org.asynchttpclient.request.body.generator.InputStreamBodyGenerator;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.util.concurrent.Future;

import static org.asynchttpclient.Dsl.*;
import static org.testng.Assert.assertEquals;

public class BodyChunkTest extends AbstractBasicTest {

  private static final String MY_MESSAGE = "my message";

  @Test
  public void negativeContentTypeTest() throws Exception {

    AsyncHttpClientConfig config = config()//
            .setConnectTimeout(100)//
            .setMaxConnections(50)//
            .setRequestTimeout(5 * 60 * 1000) // 5 minutes
            .build();

    try (AsyncHttpClient client = asyncHttpClient(config)) {
      RequestBuilder requestBuilder = post(getTargetUrl())//
              .setHeader("Content-Type", "message/rfc822")//
              .setBody(new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes())));

      Future<Response> future = client.executeRequest(requestBuilder.build());

      System.out.println("waiting for response");
      Response response = future.get();
      assertEquals(response.getStatusCode(), 200);
      assertEquals(response.getResponseBody(), MY_MESSAGE);
    }
  }
}
