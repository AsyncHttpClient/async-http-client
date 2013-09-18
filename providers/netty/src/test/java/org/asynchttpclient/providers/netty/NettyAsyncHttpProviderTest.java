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

import static org.testng.Assert.assertEquals;

import java.util.concurrent.Executors;

import org.asynchttpclient.providers.netty.NettyAsyncHttpProviderConfig;
import org.testng.annotations.Test;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Response;
import org.asynchttpclient.async.AbstractBasicTest;

public class NettyAsyncHttpProviderTest extends AbstractBasicTest {

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return NettyProviderUtil.nettyProvider(config);
    }

    @Test
    public void bossThreadPoolExecutor() throws Exception {
        NettyAsyncHttpProviderConfig conf = new NettyAsyncHttpProviderConfig();
        conf.setBossExecutorService(Executors.newSingleThreadExecutor());

        AsyncHttpClientConfig cf = new AsyncHttpClientConfig.Builder().setAsyncHttpClientProviderConfig(conf).build();
        AsyncHttpClient c = getAsyncHttpClient(cf);
        try {
            Response r = c.prepareGet(getTargetUrl()).execute().get();
            assertEquals(r.getStatusCode(), 200);
        } finally {
            c.close();
        }
    }
}
