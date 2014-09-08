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

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpProvider;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.Response;
import org.asynchttpclient.async.util.EchoHandler;
import org.asynchttpclient.async.util.TestUtils;
import org.asynchttpclient.extras.registry.AsyncHttpClientFactory;
import org.asynchttpclient.extras.registry.AsyncHttpClientImplException;
import org.asynchttpclient.extras.registry.AsyncImplHelper;
import org.asynchttpclient.util.AsyncPropertiesHelper;
import org.eclipse.jetty.server.Server;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.InvocationTargetException;

import junit.extensions.PA;

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
        AsyncPropertiesHelper.reloadProperties();
    }

    @BeforeClass(alwaysRun = true)
    public void setUpBeforeTest() throws Exception {
        port = TestUtils.findFreePort();
        server = TestUtils.newJettyHttpServer(port);
        server.setHandler(new EchoHandler());
        server.start();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() throws Exception {
        setUp();
        if (server != null)
            server.stop();
    }

    public abstract AsyncHttpProvider getAsyncHttpProvider(AsyncHttpClientConfig config);

    /**
     * If the property is not found via the system property or properties file
     * the default instance of AsyncHttpClient should be returned.
     */
    // ================================================================================================================
    @Test(groups = "fast")
    public void testGetAsyncHttpClient() {
        AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient();
        Assert.assertTrue(asyncHttpClient.getClass().equals(DefaultAsyncHttpClient.class));
        assertClientWorks(asyncHttpClient);
    }

    @Test(groups = "fast")
    public void testGetAsyncHttpClientConfig() {
        AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
        Assert.assertTrue(asyncHttpClient.getClass().equals(DefaultAsyncHttpClient.class));
        assertClientWorks(asyncHttpClient);
    }

    @Test(groups = "fast")
    public void testGetAsyncHttpClientProvider() {
        AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient(getAsyncHttpProvider(null));
        Assert.assertTrue(asyncHttpClient.getClass().equals(DefaultAsyncHttpClient.class));
        assertClientWorks(asyncHttpClient);
    }

    @Test(groups = "fast")
    public void testGetAsyncHttpClientConfigAndProvider() {
        AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient(getAsyncHttpProvider(null),
                new AsyncHttpClientConfig.Builder().build());
        Assert.assertTrue(asyncHttpClient.getClass().equals(DefaultAsyncHttpClient.class));
        assertClientWorks(asyncHttpClient);
    }

    @Test(groups = "fast")
    public void testGetAsyncHttpClientStringConfig() {
        AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient(getAsyncHttpProvider(null).getClass().getName(),
                new AsyncHttpClientConfig.Builder().build());
        Assert.assertTrue(asyncHttpClient.getClass().equals(DefaultAsyncHttpClient.class));
        assertClientWorks(asyncHttpClient);
    }

    // ==================================================================================================================================

    /**
     * If the class is specified via a system property then that class should be
     * returned
     */
    // ===================================================================================================================================
    @Test(groups = "fast")
    public void testFactoryWithSystemProperty() {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, TEST_CLIENT_CLASS_NAME);
        AsyncPropertiesHelper.reloadProperties();
        Assert.assertTrue(AsyncHttpClientFactory.getAsyncHttpClient().getClass().equals(TestAsyncHttpClient.class));
    }

    @Test(groups = "fast")
    public void testGetAsyncHttpClientConfigWithSystemProperty() {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, TEST_CLIENT_CLASS_NAME);
        AsyncPropertiesHelper.reloadProperties();
        AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
        Assert.assertTrue(asyncHttpClient.getClass().equals(TestAsyncHttpClient.class));
    }

    @Test(groups = "fast")
    public void testGetAsyncHttpClientProviderWithSystemProperty() {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, TEST_CLIENT_CLASS_NAME);
        AsyncPropertiesHelper.reloadProperties();
        AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient(getAsyncHttpProvider(null));
        Assert.assertTrue(asyncHttpClient.getClass().equals(TestAsyncHttpClient.class));
    }

    @Test(groups = "fast")
    public void testGetAsyncHttpClientConfigAndProviderWithSystemProperty() {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, TEST_CLIENT_CLASS_NAME);
        AsyncPropertiesHelper.reloadProperties();
        AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient(getAsyncHttpProvider(null),
                new AsyncHttpClientConfig.Builder().build());
        Assert.assertTrue(asyncHttpClient.getClass().equals(TestAsyncHttpClient.class));
    }

    @Test(groups = "fast")
    public void testGetAsyncHttpClientStringConfigWithSystemProperty() {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, TEST_CLIENT_CLASS_NAME);
        AsyncPropertiesHelper.reloadProperties();
        AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient(getAsyncHttpProvider(null).getClass().getName(),
                new AsyncHttpClientConfig.Builder().build());
        Assert.assertTrue(asyncHttpClient.getClass().equals(TestAsyncHttpClient.class));
    }

    // ===================================================================================================================================

    /**
     * If any of the constructors of the class fail then a
     * AsyncHttpClientException is thrown.
     */
    // ===================================================================================================================================
    @Test(groups = "fast", expectedExceptions = BadAsyncHttpClientException.class)
    public void testFactoryWithBadAsyncHttpClient() {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, BAD_CLIENT_CLASS_NAME);
        AsyncPropertiesHelper.reloadProperties();
        AsyncHttpClientFactory.getAsyncHttpClient();
        Assert.fail("BadAsyncHttpClientException should have been thrown before this point");
    }

    @Test(groups = "fast")
    public void testGetAsyncHttpClientConfigWithBadAsyncHttpClient() {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, BAD_CLIENT_CLASS_NAME);
        AsyncPropertiesHelper.reloadProperties();
        try {
            AsyncHttpClientFactory.getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
        } catch (AsyncHttpClientImplException e) {
            assertException(e);
        }
        //Assert.fail("AsyncHttpClientImplException should have been thrown before this point");
    }

    @Test(groups = "fast")
    public void testGetAsyncHttpClientProviderWithBadAsyncHttpClient() {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, BAD_CLIENT_CLASS_NAME);
        AsyncPropertiesHelper.reloadProperties();
        try {
            AsyncHttpClientFactory.getAsyncHttpClient(getAsyncHttpProvider(null));
        } catch (AsyncHttpClientImplException e) {
            assertException(e);
        }
        //Assert.fail("AsyncHttpClientImplException should have been thrown before this point");
    }

    @Test(groups = "fast")
    public void testGetAsyncHttpClientConfigAndProviderWithBadAsyncHttpClient() {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, BAD_CLIENT_CLASS_NAME);
        AsyncPropertiesHelper.reloadProperties();
        try {
            AsyncHttpClientFactory.getAsyncHttpClient(getAsyncHttpProvider(null), new AsyncHttpClientConfig.Builder().build());
        } catch (AsyncHttpClientImplException e) {
            assertException(e);
        }
        //Assert.fail("AsyncHttpClientImplException should have been thrown before this point");
    }

    @Test(groups = "fast")
    public void testGetAsyncHttpClientStringConfigWithBadAsyncHttpClient() {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, BAD_CLIENT_CLASS_NAME);
        AsyncPropertiesHelper.reloadProperties();
        try {
            AsyncHttpClientFactory.getAsyncHttpClient(getAsyncHttpProvider(null).getClass().getName(),
                    new AsyncHttpClientConfig.Builder().build());
        } catch (AsyncHttpClientImplException e) {
            assertException(e);
        }
        //Assert.fail("AsyncHttpClientImplException should have been thrown before this point");
    }

    // ===================================================================================================================================

    /*
     * If the system property exists instantiate the class else if the class is
     * not found throw an AsyncHttpClientException.
     */
    @Test(groups = "fast", expectedExceptions = AsyncHttpClientImplException.class)
    public void testFactoryWithNonExistentAsyncHttpClient() {
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, NON_EXISTENT_CLIENT_CLASS_NAME);
        AsyncPropertiesHelper.reloadProperties();
        AsyncHttpClientFactory.getAsyncHttpClient();
        Assert.fail("AsyncHttpClientImplException should have been thrown before this point");
    }

    /**
     * If property is specified but the class can’t be created or found for any
     * reason subsequent calls should throw an AsyncClientException.
     */
    @Test(groups = "fast", expectedExceptions = AsyncHttpClientImplException.class)
    public void testRepeatedCallsToBadAsyncHttpClient() {
        boolean exceptionCaught = false;
        System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, NON_EXISTENT_CLIENT_CLASS_NAME);
        AsyncPropertiesHelper.reloadProperties();
        try {
            AsyncHttpClientFactory.getAsyncHttpClient();
        } catch (AsyncHttpClientImplException e) {
            exceptionCaught = true;
        }
        Assert.assertTrue(exceptionCaught, "Didn't catch exception the first time");
        exceptionCaught = false;
        try {
            AsyncHttpClientFactory.getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
        } catch (AsyncHttpClientImplException e) {
            exceptionCaught = true;
        }
        Assert.assertTrue(exceptionCaught, "Didn't catch exception the second time");
        AsyncHttpClientFactory.getAsyncHttpClient(getAsyncHttpProvider(null).getClass().getName(),
                new AsyncHttpClientConfig.Builder().build());

    }

    private void assertClientWorks(AsyncHttpClient asyncHttpClient) {
        Response response;
        try {
            response = asyncHttpClient.prepareGet("http://localhost:" + port + "/foo/test").execute().get();
            Assert.assertEquals(200, response.getStatusCode());
        } catch (Exception e) {
            Assert.fail("Failed while making call with AsyncHttpClient", e);
        } finally {
            asyncHttpClient.close();
        }
    }

    private void assertException(AsyncHttpClientImplException e) {
        InvocationTargetException t = (InvocationTargetException) e.getCause();
        Assert.assertTrue(t.getCause() instanceof BadAsyncHttpClientException);
    }

}
