/*
 * Copyright (c) 2016 AsyncHttpClient Project. All rights reserved.
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

import io.netty.handler.codec.http.HttpHeaderNames;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilderBase;
import org.asynchttpclient.SignatureCalculator;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * OAuth {@link SignatureCalculator} that delegates to {@link OAuthSignatureCalculatorInstance}s.
 * <p>
 * This class provides a thread-safe OAuth 1.0 signature calculator by maintaining a pool of
 * {@link OAuthSignatureCalculatorInstance} objects using ThreadLocal storage. Each thread gets
 * its own instance, avoiding synchronization overhead.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * ConsumerKey consumerKey = new ConsumerKey("consumer-key", "consumer-secret");
 * RequestToken requestToken = new RequestToken("request-key", "request-secret");
 * OAuthSignatureCalculator calculator = new OAuthSignatureCalculator(consumerKey, requestToken);
 *
 * AsyncHttpClient client = Dsl.asyncHttpClient();
 * client.prepareGet("https://api.example.com/protected")
 *       .setSignatureCalculator(calculator)
 *       .execute();
 * }</pre>
 */
public class OAuthSignatureCalculator implements SignatureCalculator {

  private static final ThreadLocal<OAuthSignatureCalculatorInstance> INSTANCES = ThreadLocal.withInitial(() -> {
    try {
      return new OAuthSignatureCalculatorInstance();
    } catch (NoSuchAlgorithmException e) {
      throw new ExceptionInInitializerError(e);
    }
  });

  private final ConsumerKey consumerAuth;

  private final RequestToken userAuth;

  /**
   * Creates a new OAuth signature calculator with the specified credentials.
   *
   * @param consumerAuth Consumer key to use for signature calculation
   * @param userAuth Request/access token to use for signature calculation
   */
  public OAuthSignatureCalculator(ConsumerKey consumerAuth, RequestToken userAuth) {
    this.consumerAuth = consumerAuth;
    this.userAuth = userAuth;
  }

  /**
   * Calculates the OAuth signature and adds it to the request as an Authorization header.
   * <p>
   * This method computes the OAuth 1.0 signature based on the request details and the
   * configured consumer key and request token, then sets the Authorization header on the
   * request builder.
   * </p>
   *
   * @param request the request to sign
   * @param requestBuilder the request builder to add the signature to
   * @throws IllegalArgumentException if the signature cannot be computed due to invalid keys
   */
  @Override
  public void calculateAndAddSignature(Request request, RequestBuilderBase<?> requestBuilder) {
    try {
      String authorization = INSTANCES.get().computeAuthorizationHeader(
        consumerAuth,
        userAuth,
        request.getUri(),
        request.getMethod(),
        request.getFormParams(),
        request.getQueryParams());
      requestBuilder.setHeader(HttpHeaderNames.AUTHORIZATION, authorization);
    } catch (InvalidKeyException e) {
      throw new IllegalArgumentException("Failed to compute a valid key from consumer and user secrets", e);
    }
  }
}
