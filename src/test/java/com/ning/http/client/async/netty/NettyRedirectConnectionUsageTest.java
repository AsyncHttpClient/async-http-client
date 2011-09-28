/*
 * Copyright (c) 2010-2011 Sonatype, Inc. All rights reserved.
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
package com.ning.http.client.async.netty;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.AsyncHttpProviderConfig;
import com.ning.http.client.async.ProviderUtil;
import com.ning.http.client.async.RedirectConnectionUsageTest;
import com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig;

public class NettyRedirectConnectionUsageTest extends RedirectConnectionUsageTest {
    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return ProviderUtil.nettyProvider(config);
    }

    @Override
    protected AsyncHttpProviderConfig getProviderConfig() {
        final NettyAsyncHttpProviderConfig config = 
                new NettyAsyncHttpProviderConfig();
        if (System.getProperty("blockingio") != null) {
            config.addProperty(NettyAsyncHttpProviderConfig.USE_BLOCKING_IO, "true");
        }
        return config;
    }
}
