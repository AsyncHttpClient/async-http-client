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
package com.ning.http.client.providers.netty;

import static org.testng.Assert.assertEquals;

import java.util.concurrent.Executors;

import org.testng.annotations.Test;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Response;
import com.ning.http.client.async.AbstractBasicTest;

public class NettyAsyncHttpProviderTest extends AbstractBasicTest {

    @Test
    public void bossThreadPoolExecutor() throws Throwable {
        NettyAsyncHttpProviderConfig conf = new NettyAsyncHttpProviderConfig();
        conf.addProperty(NettyAsyncHttpProviderConfig.BOSS_EXECUTOR_SERVICE, Executors.newSingleThreadExecutor());

        AsyncHttpClientConfig cf = new AsyncHttpClientConfig.Builder().setAsyncHttpClientProviderConfig(conf).build();
        AsyncHttpClient c = getAsyncHttpClient(cf);
        try {
            Response r = c.prepareGet(getTargetUrl()).execute().get();
            assertEquals(r.getStatusCode(), 200);
        } finally {
            c.close();
        }
    }

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return NettyProviderUtil.nettyProvider(config);
    }
}
