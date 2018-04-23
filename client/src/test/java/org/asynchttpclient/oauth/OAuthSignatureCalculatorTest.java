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

import org.asynchttpclient.Param;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.util.Utf8UrlEncoder;
import org.testng.annotations.Test;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.netty.handler.codec.http.HttpHeaderNames.AUTHORIZATION;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.Dsl.post;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Tests the OAuth signature behavior.
 * <p>
 * See <a href= "https://oauth.googlecode.com/svn/code/javascript/example/signature.html" >Signature Tester</a> for an online oauth signature checker.
 */
public class OAuthSignatureCalculatorTest {
  private static final String TOKEN_KEY = "nnch734d00sl2jdk";
  private static final String TOKEN_SECRET = "pfkkdhi9sl3r4s00";
  private static final String NONCE = "kllo9940pd9333jh";
  private static final long TIMESTAMP = 1191242096;
  private static final String CONSUMER_KEY = "dpf43f3p2l4k3l03";
  private static final String CONSUMER_SECRET = "kd94hf93k423kf44";

  // sample from RFC https://tools.ietf.org/html/rfc5849#section-3.4.1
  private void testSignatureBaseString(Request request) throws NoSuchAlgorithmException {
    String signatureBaseString = new OAuthSignatureCalculatorInstance()
            .signatureBaseString(//
                    new ConsumerKey("9djdj82h48djs9d2", CONSUMER_SECRET),
                    new RequestToken("kkk9d7dh3k39sjv7", TOKEN_SECRET),
                    request.getUri(),
                    request.getMethod(),
                    request.getFormParams(),
                    request.getQueryParams(),
                    137131201,
                    "7d8f3e4a").toString();

    assertEquals(signatureBaseString, "POST&"
            + "http%3A%2F%2Fexample.com%2Frequest"
            + "&a2%3Dr%2520b%26"
            + "a3%3D2%2520q%26"
            + "a3%3Da%26"
            + "b5%3D%253D%25253D%26"
            + "c%2540%3D%26"
            + "c2%3D%26"
            + "oauth_consumer_key%3D9djdj82h48djs9d2%26"
            + "oauth_nonce%3D7d8f3e4a%26"
            + "oauth_signature_method%3DHMAC-SHA1%26"
            + "oauth_timestamp%3D137131201%26"
            + "oauth_token%3Dkkk9d7dh3k39sjv7%26"
            + "oauth_version%3D1.0");
  }

  // fork above test with an OAuth token that requires encoding
  private void testSignatureBaseStringWithEncodableOAuthToken(Request request) throws NoSuchAlgorithmException {
    String signatureBaseString = new OAuthSignatureCalculatorInstance()
            .signatureBaseString(//
                    new ConsumerKey("9djdj82h48djs9d2", CONSUMER_SECRET),
                    new RequestToken("kkk9d7dh3k39sjv7", TOKEN_SECRET),
                    request.getUri(),
                    request.getMethod(),
                    request.getFormParams(),
                    request.getQueryParams(),
                    137131201,
                    Utf8UrlEncoder.percentEncodeQueryElement("ZLc92RAkooZcIO/0cctl0Q==")).toString();

    assertEquals(signatureBaseString, "POST&"
            + "http%3A%2F%2Fexample.com%2Frequest"
            + "&a2%3Dr%2520b%26"
            + "a3%3D2%2520q%26"
            + "a3%3Da%26"
            + "b5%3D%253D%25253D%26"
            + "c%2540%3D%26"
            + "c2%3D%26"
            + "oauth_consumer_key%3D9djdj82h48djs9d2%26"
            + "oauth_nonce%3DZLc92RAkooZcIO%252F0cctl0Q%253D%253D%26"
            + "oauth_signature_method%3DHMAC-SHA1%26"
            + "oauth_timestamp%3D137131201%26"
            + "oauth_token%3Dkkk9d7dh3k39sjv7%26"
            + "oauth_version%3D1.0");
  }

  @Test
  public void testSignatureBaseStringWithProperlyEncodedUri() throws NoSuchAlgorithmException {
    Request request = post("http://example.com/request?b5=%3D%253D&a3=a&c%40=&a2=r%20b")
            .addFormParam("c2", "")
            .addFormParam("a3", "2 q")
            .build();

    testSignatureBaseString(request);
    testSignatureBaseStringWithEncodableOAuthToken(request);
  }

  @Test
  public void testSignatureBaseStringWithRawUri() throws NoSuchAlgorithmException {
    // note: @ is legal so don't decode it into %40 because it won't be
    // encoded back
    // note: we don't know how to fix a = that should have been encoded as
    // %3D but who would be stupid enough to do that?
    Request request = post("http://example.com/request?b5=%3D%253D&a3=a&c%40=&a2=r b")
            .addFormParam("c2", "")
            .addFormParam("a3", "2 q")
            .build();

    testSignatureBaseString(request);
    testSignatureBaseStringWithEncodableOAuthToken(request);
  }

  // based on the reference test case from
  // http://oauth.pbwiki.com/TestCases
  @Test
  public void testGetCalculateSignature() throws NoSuchAlgorithmException, InvalidKeyException {

    Request request = get("http://photos.example.net/photos")
            .addQueryParam("file", "vacation.jpg")
            .addQueryParam("size", "original")
            .build();

    String signature = new OAuthSignatureCalculatorInstance()
            .computeSignature(new ConsumerKey(CONSUMER_KEY, CONSUMER_SECRET),
                    new RequestToken(TOKEN_KEY, TOKEN_SECRET),
                    request.getUri(),
                    request.getMethod(),
                    request.getFormParams(),
                    request.getQueryParams(),
                    TIMESTAMP,
                    NONCE);

    assertEquals(signature, "tR3+Ty81lMeYAr/Fid0kMTYa/WM=");
  }

  @Test
  public void testPostCalculateSignature() throws UnsupportedEncodingException {
    StaticOAuthSignatureCalculator calc = //
            new StaticOAuthSignatureCalculator(//
                    new ConsumerKey(CONSUMER_KEY, CONSUMER_SECRET),
                    new RequestToken(TOKEN_KEY, TOKEN_SECRET),
                    NONCE,
                    TIMESTAMP);

    final Request req = post("http://photos.example.net/photos")
            .addFormParam("file", "vacation.jpg")
            .addFormParam("size", "original")
            .setSignatureCalculator(calc)
            .build();

    // From the signature tester, POST should look like:
    // normalized parameters:
    // file=vacation.jpg&oauth_consumer_key=dpf43f3p2l4k3l03&oauth_nonce=kllo9940pd9333jh&oauth_signature_method=HMAC-SHA1&oauth_timestamp=1191242096&oauth_token=nnch734d00sl2jdk&oauth_version=1.0&size=original
    // signature base string:
    // POST&http%3A%2F%2Fphotos.example.net%2Fphotos&file%3Dvacation.jpg%26oauth_consumer_key%3Ddpf43f3p2l4k3l03%26oauth_nonce%3Dkllo9940pd9333jh%26oauth_signature_method%3DHMAC-SHA1%26oauth_timestamp%3D1191242096%26oauth_token%3Dnnch734d00sl2jdk%26oauth_version%3D1.0%26size%3Doriginal
    // signature: wPkvxykrw+BTdCcGqKr+3I+PsiM=
    // header: OAuth
    // realm="",oauth_version="1.0",oauth_consumer_key="dpf43f3p2l4k3l03",oauth_token="nnch734d00sl2jdk",oauth_timestamp="1191242096",oauth_nonce="kllo9940pd9333jh",oauth_signature_method="HMAC-SHA1",oauth_signature="wPkvxykrw%2BBTdCcGqKr%2B3I%2BPsiM%3D"

    String authHeader = req.getHeaders().get(AUTHORIZATION);
    Matcher m = Pattern.compile("oauth_signature=\"(.+?)\"").matcher(authHeader);
    assertEquals(m.find(), true);
    String encodedSig = m.group(1);
    String sig = URLDecoder.decode(encodedSig, "UTF-8");

    assertEquals(sig, "wPkvxykrw+BTdCcGqKr+3I+PsiM=");
  }

  @Test
  public void testGetWithRequestBuilder() throws UnsupportedEncodingException {
    StaticOAuthSignatureCalculator calc =
            new StaticOAuthSignatureCalculator(
                    new ConsumerKey(CONSUMER_KEY, CONSUMER_SECRET),
                    new RequestToken(TOKEN_KEY, TOKEN_SECRET),
                    NONCE,
                    TIMESTAMP);

    final Request req = get("http://photos.example.net/photos")
            .addQueryParam("file", "vacation.jpg")
            .addQueryParam("size", "original")
            .setSignatureCalculator(calc)
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

    String authHeader = req.getHeaders().get(AUTHORIZATION);
    Matcher m = Pattern.compile("oauth_signature=\"(.+?)\"").matcher(authHeader);
    assertEquals(m.find(), true);
    String encodedSig = m.group(1);
    String sig = URLDecoder.decode(encodedSig, "UTF-8");

    assertEquals(sig, "tR3+Ty81lMeYAr/Fid0kMTYa/WM=");
    assertEquals(req.getUrl(), "http://photos.example.net/photos?file=vacation.jpg&size=original");
  }

  @Test
  public void testGetWithRequestBuilderAndQuery() throws UnsupportedEncodingException {
    StaticOAuthSignatureCalculator calc = //
            new StaticOAuthSignatureCalculator(//
                    new ConsumerKey(CONSUMER_KEY, CONSUMER_SECRET),
                    new RequestToken(TOKEN_KEY, TOKEN_SECRET),
                    NONCE,
                    TIMESTAMP);

    final Request req = get("http://photos.example.net/photos?file=vacation.jpg&size=original")
            .setSignatureCalculator(calc)
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

    String authHeader = req.getHeaders().get(AUTHORIZATION);
    Matcher m = Pattern.compile("oauth_signature=\"(.+?)\"").matcher(authHeader);
    assertTrue(m.find());
    String encodedSig = m.group(1);
    String sig = URLDecoder.decode(encodedSig, "UTF-8");

    assertEquals(sig, "tR3+Ty81lMeYAr/Fid0kMTYa/WM=");
    assertEquals(req.getUrl(), "http://photos.example.net/photos?file=vacation.jpg&size=original");
    assertEquals(
            authHeader,
            "OAuth oauth_consumer_key=\"dpf43f3p2l4k3l03\", oauth_token=\"nnch734d00sl2jdk\", oauth_signature_method=\"HMAC-SHA1\", oauth_signature=\"tR3%2BTy81lMeYAr%2FFid0kMTYa%2FWM%3D\", oauth_timestamp=\"1191242096\", oauth_nonce=\"kllo9940pd9333jh\", oauth_version=\"1.0\"");
  }

  @Test
  public void testWithNullRequestToken() throws NoSuchAlgorithmException {

    final Request request = get("http://photos.example.net/photos?file=vacation.jpg&size=original").build();

    String signatureBaseString = new OAuthSignatureCalculatorInstance()
            .signatureBaseString(//
                    new ConsumerKey("9djdj82h48djs9d2", CONSUMER_SECRET),
                    new RequestToken(null, null),
                    request.getUri(),
                    request.getMethod(),
                    request.getFormParams(),
                    request.getQueryParams(),
                    137131201,
                    Utf8UrlEncoder.percentEncodeQueryElement("ZLc92RAkooZcIO/0cctl0Q==")).toString();

    assertEquals(signatureBaseString, "GET&" +
            "http%3A%2F%2Fphotos.example.net%2Fphotos&file%3Dvacation.jpg%26" +
            "oauth_consumer_key%3D9djdj82h48djs9d2%26" +
            "oauth_nonce%3DZLc92RAkooZcIO%252F0cctl0Q%253D%253D%26" +
            "oauth_signature_method%3DHMAC-SHA1%26" +
            "oauth_timestamp%3D137131201%26" +
            "oauth_version%3D1.0%26size%3Doriginal");
  }

  @Test
  public void testWithStarQueryParameterValue() throws NoSuchAlgorithmException {
    final Request request = get("http://term.ie/oauth/example/request_token.php?testvalue=*").build();

    String signatureBaseString = new OAuthSignatureCalculatorInstance()
            .signatureBaseString(
                    new ConsumerKey("key", "secret"),
                    new RequestToken(null, null),
                    request.getUri(),
                    request.getMethod(),
                    request.getFormParams(),
                    request.getQueryParams(),
                    1469019732,
                    "6ad17f97334700f3ec2df0631d5b7511").toString();

    assertEquals(signatureBaseString, "GET&" +
            "http%3A%2F%2Fterm.ie%2Foauth%2Fexample%2Frequest_token.php&"
            + "oauth_consumer_key%3Dkey%26"
            + "oauth_nonce%3D6ad17f97334700f3ec2df0631d5b7511%26"
            + "oauth_signature_method%3DHMAC-SHA1%26"
            + "oauth_timestamp%3D1469019732%26"
            + "oauth_version%3D1.0%26"
            + "testvalue%3D%252A");
  }

  @Test
  public void testSignatureGenerationWithAsteriskInPath() throws InvalidKeyException, NoSuchAlgorithmException {
    ConsumerKey consumerKey = new ConsumerKey("key", "secret");
    RequestToken requestToken = new RequestToken(null, null);
    String nonce = "6ad17f97334700f3ec2df0631d5b7511";
    long timestamp = 1469019732;

    final Request request = get("http://example.com/oauth/example/*path/wi*th/asterisks*").build();

    String expectedSignature = "cswi/v3ZqhVkTyy5MGqW841BxDA=";
    String actualSignature = new OAuthSignatureCalculatorInstance().computeSignature(
      consumerKey,
      requestToken,
      request.getUri(),
      request.getMethod(),
      request.getFormParams(),
      request.getQueryParams(),
      timestamp,
      nonce);
    assertEquals(actualSignature, expectedSignature);

    String generatedAuthHeader = new OAuthSignatureCalculatorInstance().computeAuthorizationHeader(consumerKey, requestToken, actualSignature, timestamp, nonce);
    assertTrue(generatedAuthHeader.contains("oauth_signature=\"cswi%2Fv3ZqhVkTyy5MGqW841BxDA%3D\""));
  }

  @Test
  public void testPercentEncodeKeyValues() {
    // see https://github.com/AsyncHttpClient/async-http-client/issues/1415
    String keyValue = "\u3b05\u000c\u375b";

    ConsumerKey consumer = new ConsumerKey(keyValue, "secret");
    RequestToken reqToken = new RequestToken(keyValue, "secret");
    OAuthSignatureCalculator calc = new OAuthSignatureCalculator(consumer, reqToken);

    RequestBuilder reqBuilder = new RequestBuilder()
            .setUrl("https://api.dropbox.com/1/oauth/access_token?oauth_token=%EC%AD%AE%E3%AC%82%EC%BE%B8%E7%9C%9A%E8%BD%BD%E1%94%A5%E8%AD%AF%E8%98%93%E0%B9%99%E5%9E%96%EF%92%A2%EA%BC%97%EA%90%B0%E4%8A%91%E8%97%BF%EF%A8%BB%E5%B5%B1%DA%98%E2%90%87%E2%96%96%EE%B5%B5%E7%B9%AD%E9%AD%87%E3%BE%93%E5%AF%92%EE%BC%8F%E3%A0%B2%E8%A9%AB%E1%8B%97%EC%BF%80%EA%8F%AE%ED%87%B0%E5%97%B7%E9%97%BF%E8%BF%87%E6%81%A3%E5%BB%A1%EC%86%92%E8%92%81%E2%B9%94%EB%B6%86%E9%AE%8A%E6%94%B0%EE%AC%B5%E6%A0%99%EB%8B%AD%EB%BA%81%E7%89%9F%E5%B3%B7%EA%9D%B7%EC%A4%9C%E0%BC%BA%EB%BB%B9%ED%84%A9%E8%A5%B9%E8%AF%A0%E3%AC%85%0C%E3%9D%9B%E8%B9%8B%E6%BF%8C%EB%91%98%E7%8B%B3%E7%BB%A8%E2%A7%BB%E6%A3%84%E1%AB%B2%E8%8D%93%E4%BF%98%E9%B9%B9%EF%9A%8B%E8%A5%93");
    Request req = reqBuilder.build();

    calc.calculateAndAddSignature(req, reqBuilder);
  }
}
