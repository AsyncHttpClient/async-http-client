/*
 *    Copyright (c) 2015-2026 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient;

import org.asynchttpclient.config.AsyncHttpClientConfigDefaults;
import org.asynchttpclient.config.AsyncHttpClientConfigHelper;
import org.junit.jupiter.api.Test;

import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.ASYNC_CLIENT_CONFIG_ROOT;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.LOAD_BALANCE_CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LoadBalanceConfigTest {

    @Test
    void defaultsToDefault() {
        assertEquals(LoadBalance.DEFAULT, config().build().getLoadBalance());
    }

    @Test
    void builderSetsRoundRobin() {
        AsyncHttpClientConfig config = config().setLoadBalance(LoadBalance.ROUND_ROBIN).build();
        assertEquals(LoadBalance.ROUND_ROBIN, config.getLoadBalance());
    }

    @Test
    void nullResetsToDefault() {
        assertEquals(LoadBalance.DEFAULT, config().setLoadBalance(null).build().getLoadBalance());
    }

    @Test
    void copyConstructorPreservesValue() {
        AsyncHttpClientConfig source = config().setLoadBalance(LoadBalance.ROUND_ROBIN).build();
        AsyncHttpClientConfig copy = new DefaultAsyncHttpClientConfig.Builder(source).build();
        assertEquals(LoadBalance.ROUND_ROBIN, copy.getLoadBalance());
    }

    @Test
    void builderSetsLeastConnections() {
        AsyncHttpClientConfig config = config().setLoadBalance(LoadBalance.LEAST_CONNECTIONS).build();
        assertEquals(LoadBalance.LEAST_CONNECTIONS, config.getLoadBalance());
        assertEquals(LoadBalance.LEAST_CONNECTIONS, new DefaultAsyncHttpClientConfig.Builder(config).build().getLoadBalance());
    }

    @Test
    void propertySelectsLeastConnections() {
        String property = ASYNC_CLIENT_CONFIG_ROOT + LOAD_BALANCE_CONFIG;
        String previous = System.getProperty(property);
        System.setProperty(property, "least_connections");
        AsyncHttpClientConfigHelper.reloadProperties();
        try {
            assertEquals(LoadBalance.LEAST_CONNECTIONS, AsyncHttpClientConfigDefaults.defaultLoadBalance());
            assertEquals(LoadBalance.LEAST_CONNECTIONS, config().build().getLoadBalance());
        } finally {
            if (previous != null) {
                System.setProperty(property, previous);
            } else {
                System.clearProperty(property);
            }
            AsyncHttpClientConfigHelper.reloadProperties();
        }
        assertEquals(LoadBalance.DEFAULT, config().build().getLoadBalance());
    }
}
