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
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.Response;
import org.asynchttpclient.config.AsyncHttpClientConfigHelper;
import org.asynchttpclient.test.EchoHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

import static org.asynchttpclient.test.TestUtils.addHttpConnector;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class AbstractAsyncHttpClientFactoryTest {

    public static final String TEST_CLIENT_CLASS_NAME = "org.asynchttpclient.extras.registry.TestAsyncHttpClient";
    public static final String BAD_CLIENT_CLASS_NAME = "org.asynchttpclient.extras.registry.BadAsyncHttpClient";
    public static final String NON_EXISTENT_CLIENT_CLASS_NAME = "org.asynchttpclient.extras.registry.NonExistentAsyncHttpClient";

    private static Server server;
    private static int port;

    @BeforeEach
    public void setUp() {
        PA.setValue(AsyncHttpClientFactory.class, "instantiated", false);
        PA.setValue(AsyncHttpClientFactory.class, "asyncHttpClientImplClass", null);
        System.clearProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY);
        AsyncHttpClientConfigHelper.reloadProperties();
    }

    @BeforeAll
    public static void setUpBeforeTest() throws Exception {
        server = new Server();
        ServerConnector connector = addHttpConnector(server);
        server.setHandler(new EchoHandler());
        server.start();
        port = connector.getLocalPort();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        PA.setValue(AsyncHttpClientFactory.class, "instantiated", false);
        PA.setValue(AsyncHttpClientFactory.class, "asyncHttpClientImplClass", null);
        System.clearProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY);
        AsyncHttpClientConfigHelper.reloadProperties();

        if (server != null) {
            server.stop();
        }
    }

    /**
     * If the property is not found via the system property or properties file the default instance of AsyncHttpClient should be returned.
     */
    // ================================================================================================================
    @Test
    public void testGetAsyncHttpClient() throws Exception {
        try (AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient()) {
            assertEquals(asyncHttpClient.getClass(), DefaultAsyncHttpClient.class);
            assertClientWorks(asyncHttpClient);
        }
    }

    @Test
    public void testGetAsyncHttpClientConfig() throws Exception {
        try (AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient()) {
            assertEquals(asyncHttpClient.getClass(), DefaultAsyncHttpClient.class);
            assertClientWorks(asyncHttpClient);
        }
    }

    @Test
    public void testGetAsyncHttpClientProvider() throws Exception {
        try (AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient()) {
            assertEquals(asyncHttpClient.getClass(), DefaultAsyncHttpClient.class);
            assertClientWorks(asyncHttpClient);
        }
    }

    // ==================================================================================================================================

    /**
     * If the class is specified via a system property then that class should be returned
     */
    // ===================================================================================================================================
    @Test
    public void testFactoryWithSystemProperty() throws IOException {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, TEST_CLIENT_CLASS_NAME);
        AsyncHttpClientConfigHelper.reloadProperties();
        try (AsyncHttpClient ahc = AsyncHttpClientFactory.getAsyncHttpClient()) {
            assertEquals(ahc.getClass(), TestAsyncHttpClient.class);
        }
    }

    @Test
    public void testGetAsyncHttpClientConfigWithSystemProperty() throws IOException {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, TEST_CLIENT_CLASS_NAME);
        AsyncHttpClientConfigHelper.reloadProperties();
        try (AsyncHttpClient ahc = AsyncHttpClientFactory.getAsyncHttpClient()) {
            assertEquals(ahc.getClass(), TestAsyncHttpClient.class);
        }
    }

    @Test
    public void testGetAsyncHttpClientProviderWithSystemProperty() throws IOException {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, TEST_CLIENT_CLASS_NAME);
        AsyncHttpClientConfigHelper.reloadProperties();
        try (AsyncHttpClient ahc = AsyncHttpClientFactory.getAsyncHttpClient()) {
            assertEquals(ahc.getClass(), TestAsyncHttpClient.class);
        }
    }

    // ===================================================================================================================================

    /**
     * If any of the constructors of the class fail then a AsyncHttpClientException is thrown.
     */
    // ===================================================================================================================================
    @Test
    public void testFactoryWithBadAsyncHttpClient() throws IOException {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, BAD_CLIENT_CLASS_NAME);
        AsyncHttpClientConfigHelper.reloadProperties();
        assertThrows(BadAsyncHttpClientException.class, () -> AsyncHttpClientFactory.getAsyncHttpClient());
    }

    @Test
    public void testGetAsyncHttpClientConfigWithBadAsyncHttpClient() throws IOException {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, BAD_CLIENT_CLASS_NAME);
        AsyncHttpClientConfigHelper.reloadProperties();
        try (AsyncHttpClient ahc = AsyncHttpClientFactory.getAsyncHttpClient()) {
            //
        } catch (AsyncHttpClientImplException e) {
            assertException(e);
        }
        // Assert.fail("AsyncHttpClientImplException should have been thrown before this point");
    }

    @Test
    public void testGetAsyncHttpClientProviderWithBadAsyncHttpClient() throws IOException {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, BAD_CLIENT_CLASS_NAME);
        AsyncHttpClientConfigHelper.reloadProperties();
        try (AsyncHttpClient ahc = AsyncHttpClientFactory.getAsyncHttpClient()) {
            //
        } catch (AsyncHttpClientImplException e) {
            assertException(e);
        }
        // Assert.fail("AsyncHttpClientImplException should have been thrown before this point");
    }

    // ===================================================================================================================================

    /*
     * If the system property exists instantiate the class else if the class is not found throw an AsyncHttpClientException.
     */
    @Test
    public void testFactoryWithNonExistentAsyncHttpClient() throws IOException {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, NON_EXISTENT_CLIENT_CLASS_NAME);
        AsyncHttpClientConfigHelper.reloadProperties();
        assertThrows(AsyncHttpClientImplException.class, () -> AsyncHttpClientFactory.getAsyncHttpClient());
    }

    /**
     * If property is specified but the class can’t be created or found for any reason subsequent calls should throw an AsyncClientException.
     */
    @Test
    public void testRepeatedCallsToBadAsyncHttpClient() throws IOException {
        boolean exceptionCaught = false;
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, NON_EXISTENT_CLIENT_CLASS_NAME);
        AsyncHttpClientConfigHelper.reloadProperties();
        try (AsyncHttpClient ahc = AsyncHttpClientFactory.getAsyncHttpClient()) {
            //
        } catch (AsyncHttpClientImplException e) {
            exceptionCaught = true;
        }
        assertTrue(exceptionCaught, "Didn't catch exception the first time");
        exceptionCaught = false;
        try (AsyncHttpClient ahc = AsyncHttpClientFactory.getAsyncHttpClient()) {
            //
        } catch (AsyncHttpClientImplException e) {
            exceptionCaught = true;
        }
        assertTrue(exceptionCaught, "Didn't catch exception the second time");
    }

    private void assertClientWorks(AsyncHttpClient asyncHttpClient) throws Exception {
        Response response = asyncHttpClient.prepareGet("http://localhost:" + port + "/foo/test").execute().get();
        assertEquals(200, response.getStatusCode());
    }

    private void assertException(AsyncHttpClientImplException e) {
        InvocationTargetException t = (InvocationTargetException) e.getCause();
        assertTrue(t.getCause() instanceof BadAsyncHttpClientException);
    }
}
