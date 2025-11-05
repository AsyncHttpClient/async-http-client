/*
 * Copyright (c) 2018 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.oauth;

import org.asynchttpclient.util.Utf8UrlEncoder;

/**
 * Value class for OAuth consumer keys.
 * <p>
 * Represents the consumer credentials used in OAuth 1.0 authentication, consisting of a key and a secret.
 * The key is automatically percent-encoded for use in OAuth signatures.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * ConsumerKey consumerKey = new ConsumerKey("my-consumer-key", "my-consumer-secret");
 * String key = consumerKey.getKey();
 * String encodedKey = consumerKey.getPercentEncodedKey();
 * }</pre>
 */
public class ConsumerKey {
  private final String key;
  private final String secret;
  private final String percentEncodedKey;

  /**
   * Creates a new consumer key with the specified key and secret.
   * <p>
   * The key is automatically percent-encoded using UTF-8 URL encoding for use in OAuth signatures.
   * </p>
   *
   * @param key the consumer key (public identifier)
   * @param secret the consumer secret (confidential part)
   */
  public ConsumerKey(String key, String secret) {
    this.key = key;
    this.secret = secret;
    this.percentEncodedKey = Utf8UrlEncoder.percentEncodeQueryElement(key);
  }

  /**
   * Returns the consumer key (public identifier).
   *
   * @return the consumer key
   */
  public String getKey() {
    return key;
  }

  /**
   * Returns the consumer secret (confidential part).
   *
   * @return the consumer secret
   */
  public String getSecret() {
    return secret;
  }

  /**
   * Returns the percent-encoded consumer key.
   * <p>
   * This is the URL-encoded version of the key, ready for use in OAuth signature calculations.
   * </p>
   *
   * @return the percent-encoded consumer key
   */
  public String getPercentEncodedKey() {
    return percentEncodedKey;
  }
}
