/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
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
package com.ning.http.client.async;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import org.testng.annotations.Test;

import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;

public abstract class ComplexClientTest extends AbstractBasicTest {

    @Test(groups = {"standalone", "default_provider"})
    public void multipleRequestsTest() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(null);

        String body = "hello there";

        // once
        Response response = c.preparePost(getTargetUrl())
                .setBody(body)
                .setHeader("Content-Type", "text/html")
                .execute().get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(response.getResponseBody(), body);

        // twice
        response = c.preparePost(getTargetUrl())
                .setBody(body)
                .setHeader("Content-Type", "text/html")
                .execute().get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(response.getResponseBody(), body);
        c.close();
    }

    @Test(groups = {"standalone", "default_provider"})
    public void urlWithoutSlashTest() throws Throwable {
        AsyncHttpClient c = getAsyncHttpClient(null);

        String body = "hello there";

        // once
        Response response = c.preparePost(String.format("http://127.0.0.1:%d/foo/test", port1))
                .setBody(body)
                .setHeader("Content-Type", "text/html")
                .execute().get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(response.getResponseBody(), body);
        c.close();
    }

}
