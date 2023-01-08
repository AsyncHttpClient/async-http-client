/*
 *    Copyright (c) 2017-2023 AsyncHttpClient Project. All rights reserved.
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
