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
 */
package com.ning.http.client.oauth;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ning.http.client.Param;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.uri.Uri;
import org.testng.annotations.Test;

/**
 * Tests the OAuth signature behavior.
 *
 * See <a href="https://oauth.googlecode.com/svn/code/javascript/example/signature.html">Signature Tester</a> for an
 * online oauth signature checker.
 *
 */
public class OAuthSignatureCalculatorTest {
    private static final String CONSUMER_KEY = "dpf43f3p2l4k3l03";

    private static final String CONSUMER_SECRET = "kd94hf93k423kf44";

    public static final String TOKEN_KEY = "nnch734d00sl2jdk";

    public static final String TOKEN_SECRET = "pfkkdhi9sl3r4s00";

    public static final String NONCE = "9940pd/933+3jh==";

    final static long TIMESTAMP = 1191242096;

    private static class StaticOAuthSignatureCalculator extends OAuthSignatureCalculator {
        
        private final long timestamp;
        private final String nonce;
        
        public StaticOAuthSignatureCalculator(ConsumerKey consumerAuth, RequestToken userAuth, long timestamp, String nonce) {
            super(consumerAuth, userAuth);
            this.timestamp = timestamp;   
            this.nonce = nonce;
        }
        
        @Override
        protected long generateTimestamp() {
            return timestamp;
        }

        @Override
        protected String generateNonce() {
            return nonce;
        }
    }
    
    // based on the reference test case from
    // http://oauth.pbwiki.com/TestCases
    @Test(groups = "fast")
    public void testGetCalculateSignature() {
        ConsumerKey consumer = new ConsumerKey(CONSUMER_KEY, CONSUMER_SECRET);
        RequestToken user = new RequestToken(TOKEN_KEY, TOKEN_SECRET);
        OAuthSignatureCalculator calc = new OAuthSignatureCalculator(consumer, user);
        List<Param> queryParams = new ArrayList<>();
        queryParams.add(new Param("file", "vacation.jpg"));
        queryParams.add(new Param("size", "original"));
        queryParams.add(new Param("csv", "aaa%2Cbbb")); // add percent encoded test case
        String url = "http://photos.example.net/photos";
        String sig = calc.calculateSignature("GET", Uri.create(url), TIMESTAMP, NONCE, null, queryParams);

        assertEquals(sig, "oc/GQouurhlY1gWFyaqz9/w7fAg=");
    }

    @Test(groups = "fast")
    public void testPostCalculateSignature() {
        ConsumerKey consumer = new ConsumerKey(CONSUMER_KEY, CONSUMER_SECRET);
        RequestToken user = new RequestToken(TOKEN_KEY, TOKEN_SECRET);
        OAuthSignatureCalculator calc = new StaticOAuthSignatureCalculator(consumer, user, TIMESTAMP, NONCE);

        List<Param> formParams = new ArrayList<Param>();
        formParams.add(new Param("file", "vacation.jpg"));
        formParams.add(new Param("size", "original"));
        formParams.add(new Param("csv", "aaa%2Cbbb")); // add percent encoded test case
        String url = "http://photos.example.net/photos";
        final Request req = new RequestBuilder("POST")
                .setUri(Uri.create(url))
                .setFormParams(formParams)
                .setSignatureCalculator(calc).build();

        // From the signature tester, POST should look like:
        // normalized parameters: csv=aaa%2Cbbb&file=vacation.jpg&oauth_consumer_key=dpf43f3p2l4k3l03&oauth_nonce=9940pd%2F933%2B3jh%3D%3D&oauth_signature_method=HMAC-SHA1&oauth_timestamp=1191242096&oauth_token=nnch734d00sl2jdk&oauth_version=1.0&size=original
        // signature base string: POST&http%3A%2F%2Fphotos.example.net%2Fphotos&csv%3Daaa%252Cbbb%26file%3Dvacation.jpg%26oauth_consumer_key%3Ddpf43f3p2l4k3l03%26oauth_nonce%3D9940pd%252F933%252B3jh%253D%253D%26oauth_signature_method%3DHMAC-SHA1%26oauth_timestamp%3D1191242096%26oauth_token%3Dnnch734d00sl2jdk%26oauth_version%3D1.0%26size%3Doriginal
        // signature: pxSjRMay8t6HUKsENIGdg8QjYY4=
        // header: OAuth realm="",oauth_version="1.0",oauth_consumer_key="dpf43f3p2l4k3l03",oauth_token="nnch734d00sl2jdk",oauth_timestamp="1191242096",oauth_nonce="9940pd%2F933%2B3jh%3D%3D",oauth_signature_method="HMAC-SHA1",oauth_signature="pxSjRMay8t6HUKsENIGdg8QjYY4%3D"

        String authHeader = req.getHeaders().get("Authorization").get(0);
        Matcher m = Pattern.compile("oauth_signature=\"(.+?)\"").matcher(authHeader);
        assertEquals(m.find(), true);
        String encodedSig = m.group(1);
        String sig = null;
        try {
            sig = URLDecoder.decode(encodedSig, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            fail("bad encoding", e);
        }

        assertEquals(sig, "pxSjRMay8t6HUKsENIGdg8QjYY4=");
    }

    @Test(groups = "fast")
    public void testGetWithRequestBuilder() {
        ConsumerKey consumer = new ConsumerKey(CONSUMER_KEY, CONSUMER_SECRET);
        RequestToken user = new RequestToken(TOKEN_KEY, TOKEN_SECRET);
        OAuthSignatureCalculator calc = new StaticOAuthSignatureCalculator(consumer, user, TIMESTAMP, NONCE);

        List<Param> queryParams = new ArrayList<Param>();
        queryParams.add(new Param("file", "vacation.jpg"));
        queryParams.add(new Param("size", "original"));
        queryParams.add(new Param("params", "aaa bbb ccc")); // add percent encoded test case
        String url = "http://photos.example.net/photos";

        final Request req = new RequestBuilder("GET")
                .setUri(Uri.create(url))
                .setQueryParams(queryParams)
                .setSignatureCalculator(calc).build();

        final List<Param> params = req.getQueryParams();
        assertEquals(params.size(), 3);
        
        // From the signature tester, the URL should look like:
        //normalized parameters: file=vacation.jpg&oauth_consumer_key=dpf43f3p2l4k3l03&oauth_nonce=9940pd%2F933%2B3jh%3D%3D&oauth_signature_method=HMAC-SHA1&oauth_timestamp=1191242096&oauth_token=nnch734d00sl2jdk&oauth_version=1.0&params=aaa%20bbb%20ccc&size=original
        //signature base string: GET&http%3A%2F%2Fphotos.example.net%2Fphotos&file%3Dvacation.jpg%26oauth_consumer_key%3Ddpf43f3p2l4k3l03%26oauth_nonce%3D9940pd%252F933%252B3jh%253D%253D%26oauth_signature_method%3DHMAC-SHA1%26oauth_timestamp%3D1191242096%26oauth_token%3Dnnch734d00sl2jdk%26oauth_version%3D1.0%26params%3Daaa%2520bbb%2520ccc%26size%3Doriginal
        //signature: SKOUFsG+VUvS7vWaUpCSNN8RBXM=
        //Authorization header: OAuth realm="",oauth_version="1.0",oauth_consumer_key="dpf43f3p2l4k3l03",oauth_token="nnch734d00sl2jdk",oauth_timestamp="1191242096",oauth_nonce="9940pd%2F933%2B3jh%3D%3D",oauth_signature_method="HMAC-SHA1",oauth_signature="SKOUFsG%2BVUvS7vWaUpCSNN8RBXM%3D"

        String authHeader = req.getHeaders().get("Authorization").get(0);
        Matcher m = Pattern.compile("oauth_signature=\"(.+?)\"").matcher(authHeader);
        assertEquals(m.find(), true);
        String encodedSig = m.group(1);
        String sig = null;
        try {
            sig = URLDecoder.decode(encodedSig, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            fail("bad encoding", e);
        }

        assertEquals(sig, "SKOUFsG+VUvS7vWaUpCSNN8RBXM=");

    }

}
