/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 */
package org.asynchttpclient.oauth;

import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

import org.asynchttpclient.Param;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilderBase;
import org.asynchttpclient.SignatureCalculator;
import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.util.Base64;
import org.asynchttpclient.util.StandardCharsets;
import org.asynchttpclient.util.UTF8UrlEncoder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Simple OAuth signature calculator that can used for constructing client signatures
 * for accessing services that use OAuth for authorization.
 * <p/>
 * Supports most common signature inclusion and calculation methods: HMAC-SHA1 for
 * calculation, and Header inclusion as inclusion method. Nonce generation uses
 * simple random numbers with base64 encoding.
 *
 * @author tatu (tatu.saloranta@iki.fi)
 */
public class OAuthSignatureCalculator implements SignatureCalculator {
    public final static String HEADER_AUTHORIZATION = "Authorization";

    private static final String KEY_OAUTH_CONSUMER_KEY = "oauth_consumer_key";
    private static final String KEY_OAUTH_NONCE = "oauth_nonce";
    private static final String KEY_OAUTH_SIGNATURE = "oauth_signature";
    private static final String KEY_OAUTH_SIGNATURE_METHOD = "oauth_signature_method";
    private static final String KEY_OAUTH_TIMESTAMP = "oauth_timestamp";
    private static final String KEY_OAUTH_TOKEN = "oauth_token";
    private static final String KEY_OAUTH_VERSION = "oauth_version";

    private static final String OAUTH_VERSION_1_0 = "1.0";
    private static final String OAUTH_SIGNATURE_METHOD = "HMAC-SHA1";

    /**
     * To generate Nonce, need some (pseudo)randomness; no need for
     * secure variant here.
     */
    protected final Random random;

    protected final byte[] nonceBuffer = new byte[16];

    protected final ThreadSafeHMAC mac;

    protected final ConsumerKey consumerAuth;

    protected final RequestToken userAuth;

    /**
     * @param consumerAuth Consumer key to use for signature calculation
     * @param userAuth     Request/access token to use for signature calculation
     */
    public OAuthSignatureCalculator(ConsumerKey consumerAuth, RequestToken userAuth) {
        mac = new ThreadSafeHMAC(consumerAuth, userAuth);
        this.consumerAuth = consumerAuth;
        this.userAuth = userAuth;
        random = new Random(System.identityHashCode(this) + System.currentTimeMillis());
    }

    //@Override // silly 1.5; doesn't allow this for interfaces

    public void calculateAndAddSignature(Request request, RequestBuilderBase<?> requestBuilder) {
        String nonce = generateNonce();
        long timestamp = System.currentTimeMillis() / 1000L;
        String signature = calculateSignature(request.getMethod(), request.getUri(), timestamp, nonce, request.getFormParams(), request.getQueryParams());
        String headerValue = constructAuthHeader(signature, nonce, timestamp);
        requestBuilder.setHeader(HEADER_AUTHORIZATION, headerValue);
    }

    /**
     * Method for calculating OAuth signature using HMAC/SHA-1 method.
     */
    public String calculateSignature(String method, Uri uri, long oauthTimestamp, String nonce,
                                     List<Param> formParams, List<Param> queryParams) {
        StringBuilder signedText = new StringBuilder(100);
        signedText.append(method); // POST / GET etc (nothing to URL encode)
        signedText.append('&');

        /* 07-Oct-2010, tatu: URL may contain default port number; if so, need to extract
         *   from base URL.
         */
        String scheme = uri.getScheme();
        int port = uri.getPort();
        if (scheme.equals("http"))
            if (port == 80)
                port = -1;
        else if (scheme.equals("https"))
            if (port == 443)
                port = -1;
        
        StringBuilder sb = new StringBuilder().append(scheme).append("://").append(uri.getHost());
        if (port != -1)
            sb.append(':').append(port);
        if (isNonEmpty(uri.getPath()))
            sb.append(uri.getPath());
        
        String baseURL = sb.toString();
        UTF8UrlEncoder.appendEncoded(signedText, baseURL);

        /**
         * List of all query and form parameters added to this request; needed
         * for calculating request signature
         */
        OAuthParameterSet allParameters = new OAuthParameterSet();

        // start with standard OAuth parameters we need
        allParameters.add(KEY_OAUTH_CONSUMER_KEY, consumerAuth.getKey());
        allParameters.add(KEY_OAUTH_NONCE, nonce);
        allParameters.add(KEY_OAUTH_SIGNATURE_METHOD, OAUTH_SIGNATURE_METHOD);
        allParameters.add(KEY_OAUTH_TIMESTAMP, String.valueOf(oauthTimestamp));
        if (userAuth.getKey() != null) {
            allParameters.add(KEY_OAUTH_TOKEN, userAuth.getKey());
        }
        allParameters.add(KEY_OAUTH_VERSION, OAUTH_VERSION_1_0);

        if (formParams != null) {
            for (Param param : formParams) {
                allParameters.add(param.getName(), param.getValue());
            }
        }
        if (queryParams != null) {
            for (Param param : queryParams) {
                allParameters.add(param.getName(), param.getValue());
            }
        }
        String encodedParams = allParameters.sortAndConcat();

        // and all that needs to be URL encoded (... again!)
        signedText.append('&');
        UTF8UrlEncoder.appendEncoded(signedText, encodedParams);

        byte[] rawBase = signedText.toString().getBytes(StandardCharsets.UTF_8);
        byte[] rawSignature = mac.digest(rawBase);
        // and finally, base64 encoded... phew!
        return Base64.encode(rawSignature);
    }

    /**
     * Method used for constructing
     */
    public String constructAuthHeader(String signature, String nonce, long oauthTimestamp) {
        StringBuilder sb = new StringBuilder(200);
        sb.append("OAuth ");
        sb.append(KEY_OAUTH_CONSUMER_KEY).append("=\"").append(consumerAuth.getKey()).append("\", ");
        if (userAuth.getKey() != null) {
            sb.append(KEY_OAUTH_TOKEN).append("=\"").append(userAuth.getKey()).append("\", ");
        }
        sb.append(KEY_OAUTH_SIGNATURE_METHOD).append("=\"").append(OAUTH_SIGNATURE_METHOD).append("\", ");

        // careful: base64 has chars that need URL encoding:
        sb.append(KEY_OAUTH_SIGNATURE).append("=\"");
        UTF8UrlEncoder.appendEncoded(sb, signature).append("\", ");
        sb.append(KEY_OAUTH_TIMESTAMP).append("=\"").append(oauthTimestamp).append("\", ");

        // also: nonce may contain things that need URL encoding (esp. when using base64):
        sb.append(KEY_OAUTH_NONCE).append("=\"");
        UTF8UrlEncoder.appendEncoded(sb, nonce);
        sb.append("\", ");

        sb.append(KEY_OAUTH_VERSION).append("=\"").append(OAUTH_VERSION_1_0).append("\"");
        return sb.toString();
    }

    private synchronized String generateNonce() {
        random.nextBytes(nonceBuffer);
        // let's use base64 encoding over hex, slightly more compact than hex or decimals
        return Base64.encode(nonceBuffer);
//      return String.valueOf(Math.abs(random.nextLong()));
    }

    /**
     * Container for parameters used for calculating OAuth signature.
     * About the only confusing aspect is that of whether entries are to be sorted
     * before encoded or vice versa: if my reading is correct, encoding is to occur
     * first, then sorting; although this should rarely matter (since sorting is primary
     * by key, which usually has nothing to encode)... of course, rarely means that
     * when it would occur it'd be harder to track down.
     */
    final static class OAuthParameterSet {
        final private ArrayList<Parameter> allParameters = new ArrayList<Parameter>();

        public OAuthParameterSet() {
        }

        public OAuthParameterSet add(String key, String value) {
            Parameter p = new Parameter(UTF8UrlEncoder.encode(key), UTF8UrlEncoder.encode(value));
            allParameters.add(p);
            return this;
        }

        public String sortAndConcat() {
            // then sort them (AFTER encoding, important)
            Parameter[] params = allParameters.toArray(new Parameter[allParameters.size()]);
            Arrays.sort(params);

            // and build parameter section using pre-encoded pieces:
            StringBuilder encodedParams = new StringBuilder(100);
            for (Parameter param : params) {
                if (encodedParams.length() > 0) {
                    encodedParams.append('&');
                }
                encodedParams.append(param.key()).append('=').append(param.value());
            }
            return encodedParams.toString();
        }
    }

    /**
     * Helper class for sorting query and form parameters that we need
     */
    final static class Parameter implements Comparable<Parameter> {
        private final String key, value;

        public Parameter(String key, String value) {
            this.key = key;
            this.value = value;
        }

        public String key() {
            return key;
        }

        public String value() {
            return value;
        }

        //@Override // silly 1.5; doesn't allow this for interfaces

        public int compareTo(Parameter other) {
            int diff = key.compareTo(other.key);
            if (diff == 0) {
                diff = value.compareTo(other.value);
            }
            return diff;
        }

        @Override
        public String toString() {
            return key + "=" + value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Parameter parameter = (Parameter) o;

            if (!key.equals(parameter.key)) return false;
            if (!value.equals(parameter.value)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = key.hashCode();
            result = 31 * result + value.hashCode();
            return result;
        }
    }
}
