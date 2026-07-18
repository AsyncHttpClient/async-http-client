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

import org.junit.jupiter.api.Test;

import static org.asynchttpclient.Dsl.config;
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
}
