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

import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ComplexClientTest extends AbstractBasicTest {

    @RepeatedIfExceptionsTest(repeats = 10)
    public void multipleRequestsTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            String body = "hello there";

            // once
            Response response = client.preparePost(getTargetUrl())
                    .setBody(body)
                    .setHeader("Content-Type", "text/html")
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(response.getResponseBody(), body);

            // twice
            response = client.preparePost(getTargetUrl())
                    .setBody(body)
                    .setHeader("Content-Type", "text/html")
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(body, response.getResponseBody());
        }
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void urlWithoutSlashTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            String body = "hello there";
            Response response = client.preparePost(String.format("http://localhost:%d/foo/test", port1))
                    .setBody(body)
                    .setHeader("Content-Type", "text/html")
                    .execute()
                    .get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(body, response.getResponseBody());
        }
    }
}
