package org.asynchttpclient;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import org.asynchttpclient.async.util.EchoHandler;
import org.asynchttpclient.async.util.TestUtils;
import org.asynchttpclient.util.AsyncImplHelper;
import org.asynchttpclient.util.PA;
import org.eclipse.jetty.server.Server;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public abstract class AbstractAsyncHttpClientFactoryTest {

	private Server server;
	private int port;

	@BeforeMethod
	public void setUp() {
		PA.setValue(AsyncHttpClientFactory.class, "instantiated", false);
		PA.setValue(AsyncHttpClientFactory.class, "asyncHttpClientImplClass", null);
		System.clearProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY);
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
		if (server != null)
			server.stop();
	}

	public abstract AsyncHttpProvider getAsyncHttpProvider(AsyncHttpClientConfig config);

	/**
	 * If the property is not found via the system property or properties file
	 * the default instance of AsyncHttpClient should be returned.
	 * 
	 * @throws IOException
	 * @throws ExecutionException
	 * @throws InterruptedException
	 */
	// ================================================================================================================
	@Test(groups = "fast")
	public void testGetAsyncHttpClient() {
		AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient();
		Assert.assertTrue(asyncHttpClient instanceof AsyncHttpClientImpl);
		assertClientWorks(asyncHttpClient);
	}

	private void assertClientWorks(AsyncHttpClient asyncHttpClient) {
		Response response;
		try {
			response = asyncHttpClient.prepareGet("http://localhost:" + port + "/foo/test").execute().get();
			Assert.assertEquals(200, response.getStatusCode());
		} catch (Exception e) {
			Assert.fail("Failed while making call with AsyncHttpClient", e);
		}

	}

	@Test(groups = "fast")
	public void testGetAsyncHttpClientConfig() {
		AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient(new AsyncHttpClientConfig.Builder()
				.build());
		Assert.assertTrue(asyncHttpClient instanceof AsyncHttpClientImpl);
		assertClientWorks(asyncHttpClient);
	}

	@Test(groups = "fast")
	public void testGetAsyncHttpClientProvider() {
		AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient(getAsyncHttpProvider(null));
		Assert.assertTrue(asyncHttpClient instanceof AsyncHttpClientImpl);
		assertClientWorks(asyncHttpClient);
	}

	@Test(groups = "fast")
	public void testGetAsyncHttpClientConfigAndProvider() {
		AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient(getAsyncHttpProvider(null),
				new AsyncHttpClientConfig.Builder().build());
		Assert.assertTrue(asyncHttpClient instanceof AsyncHttpClientImpl);
		assertClientWorks(asyncHttpClient);
	}

	@Test(groups = "fast")
	public void testGetAsyncHttpClientStringConfig() {
		AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient(getAsyncHttpProvider(null)
				.getClass().getName(), new AsyncHttpClientConfig.Builder().build());
		Assert.assertTrue(asyncHttpClient instanceof AsyncHttpClientImpl);
		assertClientWorks(asyncHttpClient);
	}

	// =============================================================================================================================================

	/**
	 * If the class is specified via a system property then that class should be
	 * returned
	 */
	// ===================================================================================================================================
	@Test(groups = "fast")
	public void testFactoryWithSystemProperty() {
		System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY,
				"org.asynchttpclient.TestAsyncHttpClient");
		Assert.assertTrue(AsyncHttpClientFactory.getAsyncHttpClient() instanceof TestAsyncHttpClient);
	}

	@Test(groups = "fast")
	public void testGetAsyncHttpClientConfigWithSystemProperty() {
		System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY,
				"org.asynchttpclient.TestAsyncHttpClient");
		AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient(new AsyncHttpClientConfig.Builder()
				.build());
		Assert.assertTrue(asyncHttpClient instanceof TestAsyncHttpClient);
	}

	@Test(groups = "fast")
	public void testGetAsyncHttpClientProviderWithSystemProperty() {
		System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY,
				"org.asynchttpclient.TestAsyncHttpClient");
		AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient(getAsyncHttpProvider(null));
		Assert.assertTrue(asyncHttpClient instanceof TestAsyncHttpClient);
	}

	@Test(groups = "fast")
	public void testGetAsyncHttpClientConfigAndProviderWithSystemProperty() {
		System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY,
				"org.asynchttpclient.TestAsyncHttpClient");
		AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient(getAsyncHttpProvider(null),
				new AsyncHttpClientConfig.Builder().build());
		Assert.assertTrue(asyncHttpClient instanceof TestAsyncHttpClient);
	}

	@Test(groups = "fast")
	public void testGetAsyncHttpClientStringConfigWithSystemProperty() {
		System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY,
				"org.asynchttpclient.TestAsyncHttpClient");
		AsyncHttpClient asyncHttpClient = AsyncHttpClientFactory.getAsyncHttpClient(getAsyncHttpProvider(null)
				.getClass().getName(), new AsyncHttpClientConfig.Builder().build());
		Assert.assertTrue(asyncHttpClient instanceof TestAsyncHttpClient);
	}

	// ===================================================================================================================================

	/**
	 * If any of the constructors of the class fail then a
	 * AsyncHttpClientException is thrown.
	 */
	// ===================================================================================================================================
	@Test(groups = "fast", expectedExceptions = AsyncHttpClientImplException.class)
	public void testFactoryWithBadAsyncHttpClient() {
		System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY,
				"org.asynchttpclient.BadAsyncHttpClient");
		AsyncHttpClientFactory.getAsyncHttpClient();
		Assert.fail();
	}

	@Test(groups = "fast", expectedExceptions = AsyncHttpClientImplException.class)
	public void testGetAsyncHttpClientConfigWithBadAsyncHttpClient() {
		System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY,
				"org.asynchttpclient.BadAsyncHttpClient");
		AsyncHttpClientFactory.getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
		Assert.fail("AsyncHttpClientImplException should have been thrown before this point");
	}

	@Test(groups = "fast", expectedExceptions = AsyncHttpClientImplException.class)
	public void testGetAsyncHttpClientProviderWithBadAsyncHttpClient() {
		System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY,
				"org.asynchttpclient.BadAsyncHttpClient");
		AsyncHttpClientFactory.getAsyncHttpClient(getAsyncHttpProvider(null));
		Assert.fail("AsyncHttpClientImplException should have been thrown before this point");
	}

	@Test(groups = "fast", expectedExceptions = AsyncHttpClientImplException.class)
	public void testGetAsyncHttpClientConfigAndProviderWithBadAsyncHttpClient() {
		System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY,
				"org.asynchttpclient.BadAsyncHttpClient");
		AsyncHttpClientFactory.getAsyncHttpClient(getAsyncHttpProvider(null),
				new AsyncHttpClientConfig.Builder().build());
		Assert.fail("AsyncHttpClientImplException should have been thrown before this point");
	}

	@Test(groups = "fast", expectedExceptions = AsyncHttpClientImplException.class)
	public void testGetAsyncHttpClientStringConfigWithBadAsyncHttpClient() {
		System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY,
				"org.asynchttpclient.BadAsyncHttpClient");
		AsyncHttpClientFactory.getAsyncHttpClient(getAsyncHttpProvider(null).getClass().getName(),
				new AsyncHttpClientConfig.Builder().build());
		Assert.fail("AsyncHttpClientImplException should have been thrown before this point");
	}

	// ===================================================================================================================================

	/*
	 * If the system property exists instantiate the class else if the class is
	 * not found throw an AsyncHttpClientException.
	 */
	@Test(groups = "fast", expectedExceptions = AsyncHttpClientImplException.class)
	public void testFactoryWithNonExistentAsyncHttpClient() {
		System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY,
				"org.asynchttpclient.NonExistentAsyncHttpClient");
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
		System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY,
				"org.asynchttpclient.NonExistentAsyncHttpClient");
		try {
			AsyncHttpClientFactory.getAsyncHttpClient();
		} catch (AsyncHttpClientImplException e) {
			exceptionCaught = true;
		}
		Assert.assertTrue(exceptionCaught);
		exceptionCaught = false;
		try {
			AsyncHttpClientFactory.getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
		} catch (AsyncHttpClientImplException e) {
			exceptionCaught = true;
		}
		Assert.assertTrue(exceptionCaught);
		AsyncHttpClientFactory.getAsyncHttpClient(getAsyncHttpProvider(null).getClass().getName(),
				new AsyncHttpClientConfig.Builder().build());

	}

}
