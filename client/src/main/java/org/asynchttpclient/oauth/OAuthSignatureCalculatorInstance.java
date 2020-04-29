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

import org.asynchttpclient.Param;
import org.asynchttpclient.SignatureCalculator;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.util.StringBuilderPool;
import org.asynchttpclient.util.StringUtils;
import org.asynchttpclient.util.Utf8UrlEncoder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Non thread-safe {@link SignatureCalculator} for OAuth1.
 * <p>
 * Supports most common signature inclusion and calculation methods: HMAC-SHA1 for calculation, and Header inclusion as inclusion method. Nonce generation uses simple random
 * numbers with base64 encoding.
 */
public class OAuthSignatureCalculatorInstance {

  private static final Pattern STAR_CHAR_PATTERN = Pattern.compile("*", Pattern.LITERAL);
  private static final Pattern PLUS_CHAR_PATTERN = Pattern.compile("+", Pattern.LITERAL);
  private static final Pattern ENCODED_TILDE_PATTERN = Pattern.compile("%7E", Pattern.LITERAL);
  private static final String KEY_OAUTH_CONSUMER_KEY = "oauth_consumer_key";
  private static final String KEY_OAUTH_NONCE = "oauth_nonce";
  private static final String KEY_OAUTH_SIGNATURE = "oauth_signature";
  private static final String KEY_OAUTH_SIGNATURE_METHOD = "oauth_signature_method";
  private static final String KEY_OAUTH_TIMESTAMP = "oauth_timestamp";
  private static final String KEY_OAUTH_TOKEN = "oauth_token";
  private static final String KEY_OAUTH_VERSION = "oauth_version";
  private static final String OAUTH_VERSION_1_0 = "1.0";
  private static final String OAUTH_SIGNATURE_METHOD = "HMAC-SHA1";
  private static final String HMAC_SHA1_ALGORITHM = "HmacSHA1";

  private final Mac mac;
  private final byte[] nonceBuffer = new byte[16];
  private final Parameters parameters = new Parameters();

  public OAuthSignatureCalculatorInstance() throws NoSuchAlgorithmException {
    mac = Mac.getInstance(HMAC_SHA1_ALGORITHM);
  }

  public String computeAuthorizationHeader(ConsumerKey consumerAuth,
                                           RequestToken userAuth,
                                           Uri uri,
                                           String method,
                                           List<Param> formParams,
                                           List<Param> queryParams) throws InvalidKeyException {
    String nonce = generateNonce();
    long timestamp = generateTimestamp();
    return computeAuthorizationHeader(consumerAuth, userAuth, uri, method, formParams, queryParams, timestamp, nonce);
  }

  private String generateNonce() {
    ThreadLocalRandom.current().nextBytes(nonceBuffer);
    // let's use base64 encoding over hex, slightly more compact than hex or decimals
    return Base64.getEncoder().encodeToString(nonceBuffer);
  }

  private static long generateTimestamp() {
    return System.currentTimeMillis() / 1000L;
  }

  String computeAuthorizationHeader(ConsumerKey consumerAuth,
                                    RequestToken userAuth,
                                    Uri uri,
                                    String method,
                                    List<Param> formParams,
                                    List<Param> queryParams,
                                    long timestamp,
                                    String nonce) throws InvalidKeyException {
    String percentEncodedNonce = Utf8UrlEncoder.percentEncodeQueryElement(nonce);
    String signature = computeSignature(consumerAuth, userAuth, uri, method, formParams, queryParams, timestamp, percentEncodedNonce);
    return computeAuthorizationHeader(consumerAuth, userAuth, signature, timestamp, percentEncodedNonce);
  }

  String computeSignature(ConsumerKey consumerAuth,
                            RequestToken userAuth,
                            Uri uri,
                            String method,
                            List<Param> formParams,
                            List<Param> queryParams,
                            long oauthTimestamp,
                            String percentEncodedNonce) throws InvalidKeyException {

    StringBuilder sb = signatureBaseString(
      consumerAuth,
      userAuth,
      uri,
      method,
      formParams,
      queryParams,
      oauthTimestamp,
      percentEncodedNonce);

    ByteBuffer rawBase = StringUtils.charSequence2ByteBuffer(sb, UTF_8);
    byte[] rawSignature = digest(consumerAuth, userAuth, rawBase);
    // and finally, base64 encoded... phew!
    return Base64.getEncoder().encodeToString(rawSignature);
  }

  StringBuilder signatureBaseString(ConsumerKey consumerAuth,
                                    RequestToken userAuth,
                                    Uri uri,
                                    String method,
                                    List<Param> formParams,
                                    List<Param> queryParams,
                                    long oauthTimestamp,
                                    String percentEncodedNonce) {

    // beware: must generate first as we're using pooled StringBuilder
    String baseUrl = uri.toBaseUrl();
    String encodedParams = encodedParams(consumerAuth, userAuth, oauthTimestamp, percentEncodedNonce, formParams, queryParams);

    StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder();
    sb.append(method); // POST / GET etc (nothing to URL encode)
    sb.append('&');
    Utf8UrlEncoder.encodeAndAppendPercentEncoded(sb, baseUrl);

    // and all that needs to be URL encoded (... again!)
    sb.append('&');
    Utf8UrlEncoder.encodeAndAppendPercentEncoded(sb, encodedParams);
    return sb;
  }

  private String encodedParams(ConsumerKey consumerAuth,
                               RequestToken userAuth,
                               long oauthTimestamp,
                               String percentEncodedNonce,
                               List<Param> formParams,
                               List<Param> queryParams) {

    parameters.reset();

    // List of all query and form parameters added to this request; needed for calculating request signature
    // Start with standard OAuth parameters we need
    parameters.add(KEY_OAUTH_CONSUMER_KEY, consumerAuth.getPercentEncodedKey())
            .add(KEY_OAUTH_NONCE, percentEncodedNonce)
            .add(KEY_OAUTH_SIGNATURE_METHOD, OAUTH_SIGNATURE_METHOD)
            .add(KEY_OAUTH_TIMESTAMP, String.valueOf(oauthTimestamp));
    if (userAuth.getKey() != null) {
      parameters.add(KEY_OAUTH_TOKEN, userAuth.getPercentEncodedKey());
    }
    parameters.add(KEY_OAUTH_VERSION, OAUTH_VERSION_1_0);

    if (formParams != null) {
      for (Param param : formParams) {
        // formParams are not already encoded
        parameters.add(Utf8UrlEncoder.percentEncodeQueryElement(param.getName()), Utf8UrlEncoder.percentEncodeQueryElement(param.getValue()));
      }
    }
    if (queryParams != null) {
      for (Param param : queryParams) {
        // queryParams are already form-url-encoded
        // but OAuth1 uses RFC3986_UNRESERVED_CHARS so * and + have to be encoded
        parameters.add(percentEncodeAlreadyFormUrlEncoded(param.getName()), percentEncodeAlreadyFormUrlEncoded(param.getValue()));
      }
    }
    return parameters.sortAndConcat();
  }

  private String percentEncodeAlreadyFormUrlEncoded(String s) {
    s = STAR_CHAR_PATTERN.matcher(s).replaceAll("%2A");
    s = PLUS_CHAR_PATTERN.matcher(s).replaceAll("%20");
    s = ENCODED_TILDE_PATTERN.matcher(s).replaceAll("~");
    return s;
  }

  private byte[] digest(ConsumerKey consumerAuth, RequestToken userAuth, ByteBuffer message) throws InvalidKeyException {
    StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder();
    Utf8UrlEncoder.encodeAndAppendQueryElement(sb, consumerAuth.getSecret());
    sb.append('&');
    if (userAuth != null && userAuth.getSecret() != null) {
      Utf8UrlEncoder.encodeAndAppendQueryElement(sb, userAuth.getSecret());
    }
    byte[] keyBytes = StringUtils.charSequence2Bytes(sb, UTF_8);
    SecretKeySpec signingKey = new SecretKeySpec(keyBytes, HMAC_SHA1_ALGORITHM);

    mac.init(signingKey);
    mac.update(message);
    return mac.doFinal();
  }

  String computeAuthorizationHeader(ConsumerKey consumerAuth, RequestToken userAuth, String signature, long oauthTimestamp, String percentEncodedNonce) {
    StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder();
    sb.append("OAuth ");
    sb.append(KEY_OAUTH_CONSUMER_KEY).append("=\"").append(consumerAuth.getPercentEncodedKey()).append("\", ");
    if (userAuth.getKey() != null) {
      sb.append(KEY_OAUTH_TOKEN).append("=\"").append(userAuth.getPercentEncodedKey()).append("\", ");
    }
    sb.append(KEY_OAUTH_SIGNATURE_METHOD).append("=\"").append(OAUTH_SIGNATURE_METHOD).append("\", ");

    // careful: base64 has chars that need URL encoding:
    sb.append(KEY_OAUTH_SIGNATURE).append("=\"");
    Utf8UrlEncoder.encodeAndAppendPercentEncoded(sb, signature).append("\", ");
    sb.append(KEY_OAUTH_TIMESTAMP).append("=\"").append(oauthTimestamp).append("\", ");

    sb.append(KEY_OAUTH_NONCE).append("=\"").append(percentEncodedNonce).append("\", ");

    sb.append(KEY_OAUTH_VERSION).append("=\"").append(OAUTH_VERSION_1_0).append("\"");
    return sb.toString();
  }
}
