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
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;

public class AsyncHttpClientRegistryTest {

  private static final String TEST_AHC = "testAhc";

  @BeforeMethod
  public void setUp() {
    System.clearProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_REGISTRY_SYSTEM_PROPERTY);
    AsyncHttpClientConfigHelper.reloadProperties();
    AsyncHttpClientRegistryImpl.getInstance().clearAllInstances();
    PA.setValue(AsyncHttpClientRegistryImpl.class, "_instance", null);
  }

  @BeforeClass
  public void setUpBeforeTest() {
    System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY, AbstractAsyncHttpClientFactoryTest.TEST_CLIENT_CLASS_NAME);
  }

  @AfterClass
  public void tearDown() {
    System.clearProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_IMPL_SYSTEM_PROPERTY);
  }

  @Test
  public void testGetAndRegister() throws IOException {
    try (AsyncHttpClient ahc = AsyncHttpClientFactory.getAsyncHttpClient()) {
      Assert.assertNull(AsyncHttpClientRegistryImpl.getInstance().get(TEST_AHC));
      Assert.assertNull(AsyncHttpClientRegistryImpl.getInstance().addOrReplace(TEST_AHC, ahc));
      Assert.assertNotNull(AsyncHttpClientRegistryImpl.getInstance().get(TEST_AHC));
    }
  }

  @Test
  public void testDeRegister() throws IOException {
    try (AsyncHttpClient ahc = AsyncHttpClientFactory.getAsyncHttpClient()) {
      Assert.assertFalse(AsyncHttpClientRegistryImpl.getInstance().unregister(TEST_AHC));
      Assert.assertNull(AsyncHttpClientRegistryImpl.getInstance().addOrReplace(TEST_AHC, ahc));
      Assert.assertTrue(AsyncHttpClientRegistryImpl.getInstance().unregister(TEST_AHC));
      Assert.assertNull(AsyncHttpClientRegistryImpl.getInstance().get(TEST_AHC));
    }
  }

  @Test
  public void testRegisterIfNew() throws IOException {
    try (AsyncHttpClient ahc = AsyncHttpClientFactory.getAsyncHttpClient()) {
      try (AsyncHttpClient ahc2 = AsyncHttpClientFactory.getAsyncHttpClient()) {
        Assert.assertNull(AsyncHttpClientRegistryImpl.getInstance().addOrReplace(TEST_AHC, ahc));
        Assert.assertFalse(AsyncHttpClientRegistryImpl.getInstance().registerIfNew(TEST_AHC, ahc2));
        Assert.assertTrue(AsyncHttpClientRegistryImpl.getInstance().get(TEST_AHC) == ahc);
        Assert.assertNotNull(AsyncHttpClientRegistryImpl.getInstance().addOrReplace(TEST_AHC, ahc2));
        Assert.assertTrue(AsyncHttpClientRegistryImpl.getInstance().get(TEST_AHC) == ahc2);
        Assert.assertTrue(AsyncHttpClientRegistryImpl.getInstance().registerIfNew(TEST_AHC + 1, ahc));
        Assert.assertTrue(AsyncHttpClientRegistryImpl.getInstance().get(TEST_AHC + 1) == ahc);
      }
    }
  }

  @Test
  public void testClearAllInstances() throws IOException {
    try (AsyncHttpClient ahc = AsyncHttpClientFactory.getAsyncHttpClient()) {
      try (AsyncHttpClient ahc2 = AsyncHttpClientFactory.getAsyncHttpClient()) {
        try (AsyncHttpClient ahc3 = AsyncHttpClientFactory.getAsyncHttpClient()) {
          Assert.assertNull(AsyncHttpClientRegistryImpl.getInstance().addOrReplace(TEST_AHC, ahc));
          Assert.assertNull(AsyncHttpClientRegistryImpl.getInstance().addOrReplace(TEST_AHC + 2, ahc2));
          Assert.assertNull(AsyncHttpClientRegistryImpl.getInstance().addOrReplace(TEST_AHC + 3, ahc3));
          Assert.assertEquals(3, AsyncHttpClientRegistryImpl.getInstance().getAllRegisteredNames().size());
          AsyncHttpClientRegistryImpl.getInstance().clearAllInstances();
          Assert.assertEquals(0, AsyncHttpClientRegistryImpl.getInstance().getAllRegisteredNames().size());
          Assert.assertNull(AsyncHttpClientRegistryImpl.getInstance().get(TEST_AHC));
          Assert.assertNull(AsyncHttpClientRegistryImpl.getInstance().get(TEST_AHC + 2));
          Assert.assertNull(AsyncHttpClientRegistryImpl.getInstance().get(TEST_AHC + 3));
        }
      }
    }
  }

  @Test
  public void testCustomAsyncHttpClientRegistry() {
    System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_REGISTRY_SYSTEM_PROPERTY, TestAsyncHttpClientRegistry.class.getName());
    AsyncHttpClientConfigHelper.reloadProperties();
    Assert.assertTrue(AsyncHttpClientRegistryImpl.getInstance() instanceof TestAsyncHttpClientRegistry);
  }

  @Test(expectedExceptions = AsyncHttpClientImplException.class)
  public void testNonExistentAsyncHttpClientRegistry() {
    System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_REGISTRY_SYSTEM_PROPERTY, AbstractAsyncHttpClientFactoryTest.NON_EXISTENT_CLIENT_CLASS_NAME);
    AsyncHttpClientConfigHelper.reloadProperties();
    AsyncHttpClientRegistryImpl.getInstance();
    Assert.fail("Should never have reached here");
  }

  @Test(expectedExceptions = AsyncHttpClientImplException.class)
  public void testBadAsyncHttpClientRegistry() {
    System.setProperty(AsyncImplHelper.ASYNC_HTTP_CLIENT_REGISTRY_SYSTEM_PROPERTY, AbstractAsyncHttpClientFactoryTest.BAD_CLIENT_CLASS_NAME);
    AsyncHttpClientConfigHelper.reloadProperties();
    AsyncHttpClientRegistryImpl.getInstance();
    Assert.fail("Should never have reached here");
  }

}
