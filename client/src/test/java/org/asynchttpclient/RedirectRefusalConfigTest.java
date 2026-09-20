/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
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

import org.asynchttpclient.config.AsyncHttpClientConfigHelper;
import org.junit.jupiter.api.Test;

import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.ASYNC_CLIENT_CONFIG_ROOT;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RedirectRefusalConfigTest {

    @Test
    public void bothArmsAreOffByDefault() {
        AsyncHttpClientConfig built = config().build();
        assertFalse(built.isRefuseSchemeDowngradeOnRedirect());
        assertFalse(built.isRefuseCrossOriginBodyOnRedirect());
    }

    @Test
    public void theBuilderSetsEachArmOnItsOwn() {
        AsyncHttpClientConfig downgradeOnly = config().setRefuseSchemeDowngradeOnRedirect(true).build();
        assertTrue(downgradeOnly.isRefuseSchemeDowngradeOnRedirect());
        assertFalse(downgradeOnly.isRefuseCrossOriginBodyOnRedirect());

        AsyncHttpClientConfig bodyOnly = config().setRefuseCrossOriginBodyOnRedirect(true).build();
        assertFalse(bodyOnly.isRefuseSchemeDowngradeOnRedirect());
        assertTrue(bodyOnly.isRefuseCrossOriginBodyOnRedirect());
    }

    /**
     * The Builder's field initialisers are the only wiring between the properties and a built config, and
     * nothing else here would notice losing them: an uninitialised boolean is false, which is the default.
     */
    @Test
    public void thePropertiesReachABuiltConfig() {
        String downgrade = ASYNC_CLIENT_CONFIG_ROOT + "refuseSchemeDowngradeOnRedirect";
        String crossOrigin = ASYNC_CLIENT_CONFIG_ROOT + "refuseCrossOriginBodyOnRedirect";
        System.setProperty(downgrade, "true");
        System.setProperty(crossOrigin, "true");
        AsyncHttpClientConfigHelper.reloadProperties();
        try {
            AsyncHttpClientConfig built = config().build();
            assertTrue(built.isRefuseSchemeDowngradeOnRedirect());
            assertTrue(built.isRefuseCrossOriginBodyOnRedirect());
        } finally {
            System.clearProperty(downgrade);
            System.clearProperty(crossOrigin);
            AsyncHttpClientConfigHelper.reloadProperties();
        }
    }

    @Test
    public void theCopyConstructorKeepsBoth() {
        AsyncHttpClientConfig original = config()
                .setRefuseSchemeDowngradeOnRedirect(true)
                .setRefuseCrossOriginBodyOnRedirect(true)
                .build();

        AsyncHttpClientConfig copy = new DefaultAsyncHttpClientConfig.Builder(original).build();
        assertTrue(copy.isRefuseSchemeDowngradeOnRedirect());
        assertTrue(copy.isRefuseCrossOriginBodyOnRedirect());
    }
}
