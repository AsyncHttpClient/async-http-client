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
package org.asynchttpclient.providers.netty;

import static org.testng.Assert.assertTrue;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.async.PerRequestTimeoutTest;

public class NettyPerRequestTimeoutTest extends PerRequestTimeoutTest {

    @Override
    protected void checkTimeoutMessage(String message) {
        assertTrue(message.startsWith("Request timed out"), "error message indicates reason of error");
        assertTrue(message.contains("127.0.0.1"), "error message contains remote ip address");
        assertTrue(message.contains("of 100 ms"), "error message contains timeout configuration value");
    }

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return NettyProviderUtil.nettyProvider(config);
    }
}
