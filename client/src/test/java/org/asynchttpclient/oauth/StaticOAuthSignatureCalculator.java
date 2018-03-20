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

import io.netty.handler.codec.http.HttpHeaderNames;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilderBase;
import org.asynchttpclient.SignatureCalculator;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

class StaticOAuthSignatureCalculator implements SignatureCalculator {

  private final ConsumerKey consumerKey;
  private final RequestToken requestToken;
  private final String nonce;
  private final long timestamp;

  StaticOAuthSignatureCalculator(ConsumerKey consumerKey, RequestToken requestToken, String nonce, long timestamp) {
    this.consumerKey = consumerKey;
    this.requestToken = requestToken;
    this.nonce = nonce;
    this.timestamp = timestamp;
  }

  @Override
  public void calculateAndAddSignature(Request request, RequestBuilderBase<?> requestBuilder) {
    try {
      String authorization = new OAuthSignatureCalculatorInstance().computeAuthorizationHeader(
        consumerKey,
        requestToken,
        request.getUri(),
        request.getMethod(),
        request.getFormParams(),
        request.getQueryParams(),
        timestamp,
        nonce);
      requestBuilder.setHeader(HttpHeaderNames.AUTHORIZATION, authorization);
    } catch (InvalidKeyException | NoSuchAlgorithmException e) {
      throw new IllegalArgumentException(e);
    }
  }
}
