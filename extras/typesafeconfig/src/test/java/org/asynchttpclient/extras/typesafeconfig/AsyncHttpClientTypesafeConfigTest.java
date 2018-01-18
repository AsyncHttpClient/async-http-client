/*
 * Copyright (c) 2018 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.extras.typesafeconfig;

import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;

@Test
public class AsyncHttpClientTypesafeConfigTest {

  public void testThreadPoolName() {
    test(AsyncHttpClientTypesafeConfig::getThreadPoolName, "threadPoolName", "MyHttpClient", "AsyncHttpClient");
  }

  public void testMaxTotalConnections() {
    test(AsyncHttpClientTypesafeConfig::getMaxConnections, "maxConnections", 100, -1);
  }

  public void testMaxConnectionPerHost() {
    test(AsyncHttpClientTypesafeConfig::getMaxConnectionsPerHost, "maxConnectionsPerHost", 100, -1);
  }

  public void testConnectTimeOut() {
    test(AsyncHttpClientTypesafeConfig::getConnectTimeout, "connectTimeout", 100, 5 * 1000);
  }

  public void testPooledConnectionIdleTimeout() {
    test(AsyncHttpClientTypesafeConfig::getPooledConnectionIdleTimeout, "pooledConnectionIdleTimeout", 200, 6 * 10000);
  }

  public void testReadTimeout() {
    test(AsyncHttpClientTypesafeConfig::getReadTimeout, "readTimeout", 100, 60 * 1000);
  }

  public void testRequestTimeout() {
    test(AsyncHttpClientTypesafeConfig::getRequestTimeout, "requestTimeout", 200, 6 * 10000);
  }

  public void testConnectionTtl() {
    test(AsyncHttpClientTypesafeConfig::getConnectionTtl, "connectionTtl", 100, -1);
  }

  public void testFollowRedirect() {
    test(AsyncHttpClientTypesafeConfig::isFollowRedirect, "followRedirect", true, false);
  }

  public void testMaxRedirects() {
    test(AsyncHttpClientTypesafeConfig::getMaxRedirects, "maxRedirects", 100, 5);
  }

  public void testCompressionEnforced() {
    test(AsyncHttpClientTypesafeConfig::isCompressionEnforced, "compressionEnforced", true, false);
  }

  public void testStrict302Handling() {
    test(AsyncHttpClientTypesafeConfig::isStrict302Handling, "strict302Handling", true, false);
  }

  public void testAllowPoolingConnection() {
    test(AsyncHttpClientTypesafeConfig::isKeepAlive, "keepAlive", false, true);
  }

  public void testMaxRequestRetry() {
    test(AsyncHttpClientTypesafeConfig::getMaxRequestRetry, "maxRequestRetry", 100, 5);
  }

  public void testDisableUrlEncodingForBoundRequests() {
    test(AsyncHttpClientTypesafeConfig::isDisableUrlEncodingForBoundRequests, "disableUrlEncodingForBoundRequests", true, false);
  }

  public void testUseInsecureTrustManager() {
    test(AsyncHttpClientTypesafeConfig::isUseInsecureTrustManager, "useInsecureTrustManager", true, false);
  }

  public void testEnabledProtocols() {
    test(AsyncHttpClientTypesafeConfig::getEnabledProtocols,
        "enabledProtocols",
        new String[]{"TLSv1.2", "TLSv1.1"},
        new String[]{"TLSv1.2", "TLSv1.1", "TLSv1"},
        Optional.of(obj -> ConfigValueFactory.fromIterable(Arrays.asList(obj)))
    );
  }

  private <T> void test(Function<AsyncHttpClientTypesafeConfig, T> func,
                        String configKey,
                        T value,
                        T defaultValue) {
    test(func, configKey, value, defaultValue, Optional.empty());
  }

  private <T> void test(Function<AsyncHttpClientTypesafeConfig, T> func,
                        String configKey,
                        T value,
                        T defaultValue,
                        Optional<Function<T, ConfigValue>> toConfigValue) {
    AsyncHttpClientTypesafeConfig defaultConfig = new AsyncHttpClientTypesafeConfig(ConfigFactory.empty());
    Assert.assertEquals(func.apply(defaultConfig), defaultValue);

    AsyncHttpClientTypesafeConfig config = new AsyncHttpClientTypesafeConfig(
        ConfigFactory.empty().withValue(configKey, toConfigValue.orElse(ConfigValueFactory::fromAnyRef).apply(value))
    );
    Assert.assertEquals(func.apply(config), value);
  }
}
