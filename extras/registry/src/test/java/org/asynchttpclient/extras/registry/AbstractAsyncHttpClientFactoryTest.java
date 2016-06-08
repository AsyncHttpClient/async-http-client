/*
 * Copyright (c) 2010-2014 Sonatype, Inc. All rights reserved.
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

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

import junit.extensions.PA;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.Response;
import org.asynchttpclient.config.AsyncHttpClientConfigHelper;
import org.asynchttpclient.test.EchoHandler;
import static org.asynchttpclient.test.TestUtils.*;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public abstract class AbstractAsyncHttpClientFactoryTest {

    public static final String TEST_CLIENT_CLASS_NAME = "org.asynchttpclient.extras.registry.TestAsyncHttpClient";
    public static final String BAD_CLIENT_CLASS_NAME = "org.asynchttpclient.extras.registry.BadAsyncHttpClient";
    public static final String NON_EXISTENT_CLIENT_CLASS_NAME = "org.asynchttpclient.extras.registry.NonExistentAsyncHttpClient";

    private Server server;
    private int port;

    @BeforeMethod
    public void setUp() {
        PA.setValue(AsyncHttpClientFactory.class, "instantiated", false);
        PA.setValue(AsyncHttpClientFactory.class, "asyncHttpClientImplClass", null);
        System.clearProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY);
        AsyncHttpClientConfigHelper.reloadProperties();
    }

    @BeforeClass(alwaysRun = true)
    public void setUpBeforeTest() throws Exception {
        server = new Server();
        ServerConnector connector = addHttpConnector(server);
        server.setHandler(new EchoHandler());
        server.start();
        port = connector.getLocalPort();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        setUp();
        if (server != null)
            server.stop();
    }

    /**
     * If the property is not found via the system property or properties file the default instance of AsyncHttpClient should be returned.
     */
    // ================================================================================================================
    @Test(groups = "standalone")
    public void testGetAsyncHttpClient() throws Exception {
        try (AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient()) {
            Assert.assertTrue(asyncHttpClient.getClass().equals(DefaultAsyncHttpClient.class));
            assertClientWorks(asyncHttpClient);
        }
    }

    @Test(groups = "standalone")
    public void testGetAsyncHttpClientConfig() throws Exception {
        try (AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient()) {
            Assert.assertTrue(asyncHttpClient.getClass().equals(DefaultAsyncHttpClient.class));
            assertClientWorks(asyncHttpClient);
        }
    }

    @Test(groups = "standalone")
    public void testGetAsyncHttpClientProvider() throws Exception {
        try (AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient()) {
            Assert.assertTrue(asyncHttpClient.getClass().equals(DefaultAsyncHttpClient.class));
            assertClientWorks(asyncHttpClient);
        }
    }

    // ==================================================================================================================================

    /**
     * If the class is specified via a system property then that class should be returned
     */
    // ===================================================================================================================================
    @Test(groups = "standalone")
    public void testFactoryWithSystemProperty() throws IOException {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, TEST_CLIENT_CLASS_NAME);
        AsyncHttpClientConfigHelper.reloadProperties();
        try (AsyncHttpClient ahc = AsyncHttpClientFactory.getAsyncHttpClient()) {
            Assert.assertTrue(ahc.getClass().equals(TestAsyncHttpClient.class));
        }
    }

    @Test(groups = "standalone")
    public void testGetAsyncHttpClientConfigWithSystemProperty() throws IOException {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, TEST_CLIENT_CLASS_NAME);
        AsyncHttpClientConfigHelper.reloadProperties();
        try (AsyncHttpClient ahc = AsyncHttpClientFactory.getAsyncHttpClient()) {
            Assert.assertTrue(ahc.getClass().equals(TestAsyncHttpClient.class));
        }
    }

    @Test(groups = "standalone")
    public void testGetAsyncHttpClientProviderWithSystemProperty() throws IOException {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, TEST_CLIENT_CLASS_NAME);
        AsyncHttpClientConfigHelper.reloadProperties();
        try (AsyncHttpClient ahc = AsyncHttpClientFactory.getAsyncHttpClient()) {
            Assert.assertTrue(ahc.getClass().equals(TestAsyncHttpClient.class));
        }
    }

    // ===================================================================================================================================

    /**
     * If any of the constructors of the class fail then a AsyncHttpClientException is thrown.
     */
    // ===================================================================================================================================
    @Test(groups = "standalone", expectedExceptions = BadAsyncHttpClientException.class)
    public void testFactoryWithBadAsyncHttpClient() throws IOException {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, BAD_CLIENT_CLASS_NAME);
        AsyncHttpClientConfigHelper.reloadProperties();
        try (AsyncHttpClient ahc = AsyncHttpClientFactory.getAsyncHttpClient()) {
            Assert.fail("BadAsyncHttpClientException should have been thrown before this point");
        }
    }

    @Test(groups = "standalone")
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

    @Test(groups = "standalone")
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
    @Test(groups = "standalone", expectedExceptions = AsyncHttpClientImplException.class)
    public void testFactoryWithNonExistentAsyncHttpClient() throws IOException {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, NON_EXISTENT_CLIENT_CLASS_NAME);
        AsyncHttpClientConfigHelper.reloadProperties();
        try (AsyncHttpClient ahc = AsyncHttpClientFactory.getAsyncHttpClient()) {
            //
        }
        Assert.fail("AsyncHttpClientImplException should have been thrown before this point");
    }

    /**
     * If property is specified but the class can’t be created or found for any reason subsequent calls should throw an AsyncClientException.
     */
    @Test(groups = "standalone", expectedExceptions = AsyncHttpClientImplException.class)
    public void testRepeatedCallsToBadAsyncHttpClient() throws IOException {
        boolean exceptionCaught = false;
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, NON_EXISTENT_CLIENT_CLASS_NAME);
        AsyncHttpClientConfigHelper.reloadProperties();
        try (AsyncHttpClient ahc = AsyncHttpClientFactory.getAsyncHttpClient()) {
            //
        } catch (AsyncHttpClientImplException e) {
            exceptionCaught = true;
        }
        Assert.assertTrue(exceptionCaught, "Didn't catch exception the first time");
        exceptionCaught = false;
        try (AsyncHttpClient ahc = AsyncHttpClientFactory.getAsyncHttpClient()) {
            //
        } catch (AsyncHttpClientImplException e) {
            exceptionCaught = true;
        }
        Assert.assertTrue(exceptionCaught, "Didn't catch exception the second time");
    }

    private void assertClientWorks(AsyncHttpClient asyncHttpClient) throws Exception {
        Response response = asyncHttpClient.prepareGet("http://localhost:" + port + "/foo/test").execute().get();
        Assert.assertEquals(200, response.getStatusCode());
    }

    private void assertException(AsyncHttpClientImplException e) {
        InvocationTargetException t = (InvocationTargetException) e.getCause();
        Assert.assertTrue(t.getCause() instanceof BadAsyncHttpClientException);
    }

}
