/*
 * Copyright (c) 2012 Sonatype, Inc. All rights reserved.
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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.fail;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Response;
import org.asynchttpclient.async.ConnectionPoolTest;
import org.testng.annotations.Test;

import java.util.concurrent.TimeUnit;

public class GrizzlyConnectionPoolTest extends ConnectionPoolTest {

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return GrizzlyProviderUtil.grizzlyProvider(config);
    }

    @Override
    @Test(enabled = false)
    public void testMaxTotalConnectionsException() {
    }

    @Override
    @Test
    public void multipleMaxConnectionOpenTest() throws Exception {
        AsyncHttpClientConfig cg = new AsyncHttpClientConfig.Builder().setAllowPoolingConnections(true).setConnectionTimeout(5000)
                .setMaxConnections(1).build();
        AsyncHttpClient c = getAsyncHttpClient(cg);
        try {
            String body = "hello there";

            // once
            Response response = c.preparePost(getTargetUrl()).setBody(body).execute().get(TIMEOUT, TimeUnit.SECONDS);

            assertEquals(response.getResponseBody(), body);

            // twice
            Exception exception = null;
            try {
                c.preparePost(String.format("http://127.0.0.1:%d/foo/test", port2)).setBody(body).execute().get(TIMEOUT, TimeUnit.SECONDS);
                fail("Should throw exception. Too many connections issued.");
            } catch (Exception ex) {
                ex.printStackTrace();
                exception = ex;
            }
            assertNotNull(exception);
        } finally {
            c.close();
        }
    }
}
