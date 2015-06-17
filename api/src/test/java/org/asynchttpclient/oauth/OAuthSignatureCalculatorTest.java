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
package org.asynchttpclient.oauth;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.asynchttpclient.Param;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.uri.Uri;
import org.testng.annotations.Test;

/**
 * Tests the OAuth signature behavior.
 *
 * See <a href=
 * "https://oauth.googlecode.com/svn/code/javascript/example/signature.html"
 * >Signature Tester</a> for an online oauth signature checker.
 *
 */
public class OAuthSignatureCalculatorTest {
    private static final String CONSUMER_KEY = "dpf43f3p2l4k3l03";

    private static final String CONSUMER_SECRET = "kd94hf93k423kf44";

    public static final String TOKEN_KEY = "nnch734d00sl2jdk";

    public static final String TOKEN_SECRET = "pfkkdhi9sl3r4s00";

    public static final String NONCE = "kllo9940pd9333jh";

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

    // sample from RFC https://tools.ietf.org/html/rfc5849#section-3.4.1
    private void testSignatureBaseString(Request request) {
        ConsumerKey consumer = new ConsumerKey("9djdj82h48djs9d2", CONSUMER_SECRET);
        RequestToken user = new RequestToken("kkk9d7dh3k39sjv7", TOKEN_SECRET);
        OAuthSignatureCalculator calc = new OAuthSignatureCalculator(consumer, user);

        String signatureBaseString = calc.signatureBaseString(//
                request.getMethod(),//
                request.getUri(),//
                137131201,//
                "7d8f3e4a",//
                request.getFormParams(),//
                request.getQueryParams()).toString();

        assertEquals(signatureBaseString, "POST&" //
                + "http%3A%2F%2Fexample.com%2Frequest" //
                + "&a2%3Dr%2520b%26"//
                + "a3%3D2%2520q%26" + "a3%3Da%26"//
                + "b5%3D%253D%25253D%26"//
                + "c%2540%3D%26"//
                + "c2%3D%26"//
                + "oauth_consumer_key%3D9djdj82h48djs9d2%26"//
                + "oauth_nonce%3D7d8f3e4a%26"//
                + "oauth_signature_method%3DHMAC-SHA1%26"//
                + "oauth_timestamp%3D137131201%26"//
                + "oauth_token%3Dkkk9d7dh3k39sjv7%26"//
                + "oauth_version%3D1.0");
    }

    @Test(groups = "fast")
    public void testSignatureBaseStringWithProperlyEncodedUri() {

        Request request = new RequestBuilder("POST")//
                .setUrl("http://example.com/request?b5=%3D%253D&a3=a&c%40=&a2=r%20b")//
                .addFormParam("c2", "")//
                .addFormParam("a3", "2 q")//
                .build();

        testSignatureBaseString(request);
    }

    @Test(groups = "fast")
    public void testSignatureBaseStringWithRawUri() {

        // note: @ is legal so don't decode it into %40 because it won't be
        // encoded back
        // note: we don't know how to fix a = that should have been encoded as
        // %3D but who would be stupid enough to do that?
        Request request = new RequestBuilder("POST")//
                .setUrl("http://example.com/request?b5=%3D%253D&a3=a&c%40=&a2=r b")//
                .addFormParam("c2", "")//
                .addFormParam("a3", "2 q")//
                .build();

        testSignatureBaseString(request);
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
        String url = "http://photos.example.net/photos";
        String sig = calc.calculateSignature("GET", Uri.create(url), TIMESTAMP, NONCE, null, queryParams);

        assertEquals(sig, "tR3+Ty81lMeYAr/Fid0kMTYa/WM=");
    }

    @Test(groups = "fast")
    public void testPostCalculateSignature() {
        ConsumerKey consumer = new ConsumerKey(CONSUMER_KEY, CONSUMER_SECRET);
        RequestToken user = new RequestToken(TOKEN_KEY, TOKEN_SECRET);
        OAuthSignatureCalculator calc = new StaticOAuthSignatureCalculator(consumer, user, TIMESTAMP, NONCE);

        List<Param> formParams = new ArrayList<Param>();
        formParams.add(new Param("file", "vacation.jpg"));
        formParams.add(new Param("size", "original"));
        String url = "http://photos.example.net/photos";
        final Request req = new RequestBuilder("POST")//
                .setUri(Uri.create(url))//
                .setFormParams(formParams)//
                .setSignatureCalculator(calc)//
                .build();

        // From the signature tester, POST should look like:
        // normalized parameters:
        // file=vacation.jpg&oauth_consumer_key=dpf43f3p2l4k3l03&oauth_nonce=kllo9940pd9333jh&oauth_signature_method=HMAC-SHA1&oauth_timestamp=1191242096&oauth_token=nnch734d00sl2jdk&oauth_version=1.0&size=original
        // signature base string:
        // POST&http%3A%2F%2Fphotos.example.net%2Fphotos&file%3Dvacation.jpg%26oauth_consumer_key%3Ddpf43f3p2l4k3l03%26oauth_nonce%3Dkllo9940pd9333jh%26oauth_signature_method%3DHMAC-SHA1%26oauth_timestamp%3D1191242096%26oauth_token%3Dnnch734d00sl2jdk%26oauth_version%3D1.0%26size%3Doriginal
        // signature: wPkvxykrw+BTdCcGqKr+3I+PsiM=
        // header: OAuth
        // realm="",oauth_version="1.0",oauth_consumer_key="dpf43f3p2l4k3l03",oauth_token="nnch734d00sl2jdk",oauth_timestamp="1191242096",oauth_nonce="kllo9940pd9333jh",oauth_signature_method="HMAC-SHA1",oauth_signature="wPkvxykrw%2BBTdCcGqKr%2B3I%2BPsiM%3D"

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

        assertEquals(sig, "wPkvxykrw+BTdCcGqKr+3I+PsiM=");
    }

    @Test(groups = "fast")
    public void testGetWithRequestBuilder() {
        ConsumerKey consumer = new ConsumerKey(CONSUMER_KEY, CONSUMER_SECRET);
        RequestToken user = new RequestToken(TOKEN_KEY, TOKEN_SECRET);
        OAuthSignatureCalculator calc = new StaticOAuthSignatureCalculator(consumer, user, TIMESTAMP, NONCE);

        List<Param> queryParams = new ArrayList<Param>();
        queryParams.add(new Param("file", "vacation.jpg"));
        queryParams.add(new Param("size", "original"));
        String url = "http://photos.example.net/photos";

        final Request req = new RequestBuilder("GET")//
                .setUri(Uri.create(url))//
                .setQueryParams(queryParams)//
                .setSignatureCalculator(calc)//
                .build();

        final List<Param> params = req.getQueryParams();
        assertEquals(params.size(), 2);

        // From the signature tester, the URL should look like:
        // normalized parameters:
        // file=vacation.jpg&oauth_consumer_key=dpf43f3p2l4k3l03&oauth_nonce=kllo9940pd9333jh&oauth_signature_method=HMAC-SHA1&oauth_timestamp=1191242096&oauth_token=nnch734d00sl2jdk&oauth_version=1.0&size=original
        // signature base string:
        // GET&http%3A%2F%2Fphotos.example.net%2Fphotos&file%3Dvacation.jpg%26oauth_consumer_key%3Ddpf43f3p2l4k3l03%26oauth_nonce%3Dkllo9940pd9333jh%26oauth_signature_method%3DHMAC-SHA1%26oauth_timestamp%3D1191242096%26oauth_token%3Dnnch734d00sl2jdk%26oauth_version%3D1.0%26size%3Doriginal
        // signature: tR3+Ty81lMeYAr/Fid0kMTYa/WM=
        // Authorization header: OAuth
        // realm="",oauth_version="1.0",oauth_consumer_key="dpf43f3p2l4k3l03",oauth_token="nnch734d00sl2jdk",oauth_timestamp="1191242096",oauth_nonce="kllo9940pd9333jh",oauth_signature_method="HMAC-SHA1",oauth_signature="tR3%2BTy81lMeYAr%2FFid0kMTYa%2FWM%3D"

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

        assertEquals(sig, "tR3+Ty81lMeYAr/Fid0kMTYa/WM=");

    }

}
