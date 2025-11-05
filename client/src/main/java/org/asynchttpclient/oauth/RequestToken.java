/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
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
 * Value class used for OAuth tokens (request token or access token).
 * <p>
 * This is a simple container with two parts: a public identifier ("key") and a
 * confidential ("secret") part. The key is automatically percent-encoded for use
 * in OAuth signatures.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * RequestToken requestToken = new RequestToken("request-key", "request-secret");
 * String key = requestToken.getKey();
 * String encodedKey = requestToken.getPercentEncodedKey();
 * }</pre>
 */
public class RequestToken {
  private final String key;
  private final String secret;
  private final String percentEncodedKey;

  /**
   * Creates a new request token with the specified key and secret.
   * <p>
   * The key is automatically percent-encoded using UTF-8 URL encoding for use in OAuth signatures.
   * </p>
   *
   * @param key the token key (public identifier)
   * @param token the token secret (confidential part)
   */
  public RequestToken(String key, String token) {
    this.key = key;
    this.secret = token;
    this.percentEncodedKey = Utf8UrlEncoder.percentEncodeQueryElement(key);
  }

  /**
   * Returns the token key (public identifier).
   *
   * @return the token key
   */
  public String getKey() {
    return key;
  }

  /**
   * Returns the token secret (confidential part).
   *
   * @return the token secret
   */
  public String getSecret() {
    return secret;
  }

  /**
   * Returns the percent-encoded token key.
   * <p>
   * This is the URL-encoded version of the key, ready for use in OAuth signature calculations.
   * </p>
   *
   * @return the percent-encoded token key
   */
  public String getPercentEncodedKey() {
    return percentEncodedKey;
  }
}
