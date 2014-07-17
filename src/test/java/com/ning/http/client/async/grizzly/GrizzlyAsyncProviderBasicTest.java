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

package com.ning.http.client.async.grizzly;

import static com.ning.http.client.providers.grizzly.GrizzlyAsyncHttpProviderConfig.Property.TRANSPORT_CUSTOMIZER;

import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.strategies.SameThreadIOStrategy;
import org.testng.annotations.Test;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.AsyncHttpProviderConfig;
import com.ning.http.client.async.AsyncProvidersBasicTest;
import com.ning.http.client.async.ProviderUtil;
import com.ning.http.client.providers.grizzly.GrizzlyAsyncHttpProviderConfig;
import com.ning.http.client.providers.grizzly.TransportCustomizer;

public class GrizzlyAsyncProviderBasicTest extends AsyncProvidersBasicTest {

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return ProviderUtil.grizzlyProvider(config);
    }

    @Override
    protected AsyncHttpProviderConfig<?, ?> getProviderConfig() {
        final GrizzlyAsyncHttpProviderConfig config = new GrizzlyAsyncHttpProviderConfig();
        config.addProperty(TRANSPORT_CUSTOMIZER, new TransportCustomizer() {
            @Override
            public void customize(TCPNIOTransport transport, FilterChainBuilder builder) {
                transport.setTcpNoDelay(true);
                transport.setIOStrategy(SameThreadIOStrategy.getInstance());
            }
        });
        return config;
    }

    @Override
    protected String generatedAcceptEncodingHeader() {
        return "gzip";
    }
    
    // FIXME server replies with a foo=bar cookie and yet Grizzly decodes it into foo=value; domain=/; path=/
    // see https://github.com/AsyncHttpClient/async-http-client/issues/631
    @Test(groups = { "standalone", "default_provider", "async" }, enabled = false)
    public void asyncDoGetCookieTest() throws Throwable {
    }
}
