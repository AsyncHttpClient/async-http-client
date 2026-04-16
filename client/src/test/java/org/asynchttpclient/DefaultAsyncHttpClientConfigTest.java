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

import org.testng.annotations.Test;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class DefaultAsyncHttpClientConfigTest {

  @Test
  public void testStripAuthorizationOnRedirect_DefaultIsFalse() {
    DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder().build();
    assertFalse(config.isStripAuthorizationOnRedirect(), "Default should be false");
  }

  @Test
  public void testStripAuthorizationOnRedirect_SetTrue() {
    DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
            .setStripAuthorizationOnRedirect(true)
            .build();
    assertTrue(config.isStripAuthorizationOnRedirect(), "Should be true when set");
  }

  @Test
  public void testStripAuthorizationOnRedirect_SetFalse() {
    DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
            .setStripAuthorizationOnRedirect(false)
            .build();
    assertFalse(config.isStripAuthorizationOnRedirect(), "Should be false when set to false");
  }
}
