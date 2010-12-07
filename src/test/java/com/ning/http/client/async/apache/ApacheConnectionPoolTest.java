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
package com.ning.http.client.async.apache;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.async.ConnectionPoolTest;
import com.ning.http.client.async.ProviderUtil;
import org.testng.annotations.Test;

public class ApacheConnectionPoolTest extends ConnectionPoolTest {

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return ProviderUtil.apacheProvider(config);
    }

    @Test(enabled = false)
    public void testMaxTotalConnections() {
    }

    @Test(enabled = false)
    public void asyncDoGetKeepAliveHandlerTest_channelClosedDoesNotFail() throws Throwable {
    }

    @Test(enabled = false)
    public void testInvalidConnectionsPool() {
    }

    @Test(enabled = false)
    public void testValidConnectionsPool() {
    }

    @Test(enabled = false)
    public void multipleMaxConnectionOpenTest() throws Throwable {
    }

    @Test(enabled = false)
    public void multipleMaxConnectionOpenTestWithQuery() throws Throwable {
    }

    @Test(enabled = false)
    public void asyncDoGetMaxConnectionsTest() throws Throwable {
    }

    @Test(enabled = false)
    public void win7DisconnectTest() throws Throwable {
    }

    @Test(enabled = false)
    public void asyncHandlerOnThrowableTest() throws Throwable {
    }
}
