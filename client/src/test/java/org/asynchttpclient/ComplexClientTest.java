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

import org.testng.annotations.Test;

import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.testng.Assert.assertEquals;

public class ComplexClientTest extends AbstractBasicTest {

  @Test
  public void multipleRequestsTest() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      String body = "hello there";

      // once
      Response response = c.preparePost(getTargetUrl()).setBody(body).setHeader("Content-Type", "text/html").execute().get(TIMEOUT, TimeUnit.SECONDS);

      assertEquals(response.getResponseBody(), body);

      // twice
      response = c.preparePost(getTargetUrl()).setBody(body).setHeader("Content-Type", "text/html").execute().get(TIMEOUT, TimeUnit.SECONDS);

      assertEquals(response.getResponseBody(), body);
    }
  }

  @Test
  public void urlWithoutSlashTest() throws Exception {
    try (AsyncHttpClient c = asyncHttpClient()) {
      String body = "hello there";
      Response response = c.preparePost(String.format("http://localhost:%d/foo/test", port1)).setBody(body).setHeader("Content-Type", "text/html").execute().get(TIMEOUT, TimeUnit.SECONDS);
      assertEquals(response.getResponseBody(), body);
    }
  }
}
