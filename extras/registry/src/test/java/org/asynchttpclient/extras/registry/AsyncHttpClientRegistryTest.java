/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.extras.registry;

import junit.extensions.PA;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.config.AsyncHttpClientConfigHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AsyncHttpClientRegistryTest {

    private static final String TEST_AHC = "testAhc";

    @BeforeEach
    public void setUp() {
        System.clearProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_REGISTRY_SYSTEM_PROPERTY);
        AsyncHttpClientConfigHelper.reloadProperties();
        AsyncHttpClientRegistryImpl.getInstance().clearAllInstances();
        PA.setValue(AsyncHttpClientRegistryImpl.class, "_instance", null);
    }

    @BeforeAll
    public void setUpBeforeTest() {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, AbstractAsyncHttpClientFactoryTest.TEST_CLIENT_CLASS_NAME);
    }

    @AfterAll
    public void tearDown() {
        System.clearProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY);
    }

    @Test
    public void testGetAndRegister() throws IOException {
        try (AsyncHttpClient ahc = AsyncHttpClientFactory.getAsyncHttpClient()) {
            assertNull(AsyncHttpClientRegistryImpl.getInstance().get(TEST_AHC));
            assertNull(AsyncHttpClientRegistryImpl.getInstance().addOrReplace(TEST_AHC, ahc));
            assertNotNull(AsyncHttpClientRegistryImpl.getInstance().get(TEST_AHC));
        }
    }

    @Test
    public void testDeRegister() throws IOException {
        try (AsyncHttpClient ahc = AsyncHttpClientFactory.getAsyncHttpClient()) {
            assertFalse(AsyncHttpClientRegistryImpl.getInstance().unregister(TEST_AHC));
            assertNull(AsyncHttpClientRegistryImpl.getInstance().addOrReplace(TEST_AHC, ahc));
            assertTrue(AsyncHttpClientRegistryImpl.getInstance().unregister(TEST_AHC));
            assertNull(AsyncHttpClientRegistryImpl.getInstance().get(TEST_AHC));
        }
    }

    @Test
    public void testRegisterIfNew() throws IOException {
        try (AsyncHttpClient ahc = AsyncHttpClientFactory.getAsyncHttpClient()) {
            try (AsyncHttpClient ahc2 = AsyncHttpClientFactory.getAsyncHttpClient()) {
                assertNull(AsyncHttpClientRegistryImpl.getInstance().addOrReplace(TEST_AHC, ahc));
                assertFalse(AsyncHttpClientRegistryImpl.getInstance().registerIfNew(TEST_AHC, ahc2));
                assertSame(AsyncHttpClientRegistryImpl.getInstance().get(TEST_AHC), ahc);
                assertNotNull(AsyncHttpClientRegistryImpl.getInstance().addOrReplace(TEST_AHC, ahc2));
                assertSame(AsyncHttpClientRegistryImpl.getInstance().get(TEST_AHC), ahc2);
                assertTrue(AsyncHttpClientRegistryImpl.getInstance().registerIfNew(TEST_AHC + 1, ahc));
                assertSame(AsyncHttpClientRegistryImpl.getInstance().get(TEST_AHC + 1), ahc);
            }
        }
    }

    @Test
    public void testClearAllInstances() throws IOException {
        try (AsyncHttpClient ahc = AsyncHttpClientFactory.getAsyncHttpClient()) {
            try (AsyncHttpClient ahc2 = AsyncHttpClientFactory.getAsyncHttpClient()) {
                try (AsyncHttpClient ahc3 = AsyncHttpClientFactory.getAsyncHttpClient()) {
                    assertNull(AsyncHttpClientRegistryImpl.getInstance().addOrReplace(TEST_AHC, ahc));
                    assertNull(AsyncHttpClientRegistryImpl.getInstance().addOrReplace(TEST_AHC + 2, ahc2));
                    assertNull(AsyncHttpClientRegistryImpl.getInstance().addOrReplace(TEST_AHC + 3, ahc3));
                    assertEquals(3, AsyncHttpClientRegistryImpl.getInstance().getAllRegisteredNames().size());
                    AsyncHttpClientRegistryImpl.getInstance().clearAllInstances();
                    assertEquals(0, AsyncHttpClientRegistryImpl.getInstance().getAllRegisteredNames().size());
                    assertNull(AsyncHttpClientRegistryImpl.getInstance().get(TEST_AHC));
                    assertNull(AsyncHttpClientRegistryImpl.getInstance().get(TEST_AHC + 2));
                    assertNull(AsyncHttpClientRegistryImpl.getInstance().get(TEST_AHC + 3));
                }
            }
        }
    }

    @Test
    public void testCustomAsyncHttpClientRegistry() {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_REGISTRY_SYSTEM_PROPERTY, TestAsyncHttpClientRegistry.class.getName());
        AsyncHttpClientConfigHelper.reloadProperties();
        assertTrue(AsyncHttpClientRegistryImpl.getInstance() instanceof TestAsyncHttpClientRegistry);
    }

    @Test
    public void testNonExistentAsyncHttpClientRegistry() {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_REGISTRY_SYSTEM_PROPERTY, AbstractAsyncHttpClientFactoryTest.NON_EXISTENT_CLIENT_CLASS_NAME);
        AsyncHttpClientConfigHelper.reloadProperties();
        assertThrows(AsyncHttpClientImplException.class, () -> AsyncHttpClientRegistryImpl.getInstance());
    }

    @Test
    public void testBadAsyncHttpClientRegistry() {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_REGISTRY_SYSTEM_PROPERTY, AbstractAsyncHttpClientFactoryTest.BAD_CLIENT_CLASS_NAME);
        AsyncHttpClientConfigHelper.reloadProperties();
        assertThrows(AsyncHttpClientImplException.class, () -> AsyncHttpClientRegistryImpl.getInstance());
    }
}
