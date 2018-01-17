/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Dsl;
import org.asynchttpclient.Param;
import org.asynchttpclient.Request;
import org.asynchttpclient.netty.util.ByteBufUtils;
import org.asynchttpclient.uri.Uri;
import org.testng.annotations.Test;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderValues.APPLICATION_JSON;
import static java.nio.charset.StandardCharsets.*;
import static org.testng.Assert.*;

public class HttpUtilsTest {

  private static String toUsAsciiString(ByteBuffer buf) throws CharacterCodingException {
    ByteBuf bb = Unpooled.wrappedBuffer(buf);
    try {
      return ByteBufUtils.byteBuf2String(US_ASCII, bb);
    } finally {
      bb.release();
    }
  }

  @Test
  public void testGetAuthority() {
    Uri uri = Uri.create("http://stackoverflow.com/questions/17814461/jacoco-maven-testng-0-test-coverage");
    String authority = HttpUtils.getAuthority(uri);
    assertEquals(authority, "stackoverflow.com:80", "Incorrect authority returned from getAuthority");
  }

  @Test
  public void testGetAuthorityWithPortInUrl() {
    Uri uri = Uri.create("http://stackoverflow.com:8443/questions/17814461/jacoco-maven-testng-0-test-coverage");
    String authority = HttpUtils.getAuthority(uri);
    assertEquals(authority, "stackoverflow.com:8443", "Incorrect authority returned from getAuthority");
  }

  @Test
  public void testGetBaseUrl() {
    Uri uri = Uri.create("http://stackoverflow.com:8443/questions/17814461/jacoco-maven-testng-0-test-coverage");
    String baseUrl = HttpUtils.getBaseUrl(uri);
    assertEquals(baseUrl, "http://stackoverflow.com:8443", "Incorrect base URL returned from getBaseURL");
  }

  @Test
  public void testIsSameBaseUrlReturnsFalseWhenPortDifferent() {
    Uri uri1 = Uri.create("http://stackoverflow.com:8443/questions/17814461/jacoco-maven-testng-0-test-coverage");
    Uri uri2 = Uri.create("http://stackoverflow.com:8442/questions/1057564/pretty-git-branch-graphs");
    assertFalse(HttpUtils.isSameBase(uri1, uri2), "Base URLs should be different, but true was returned from isSameBase");
  }

  @Test
  public void testIsSameBaseUrlReturnsFalseWhenSchemeDifferent() {
    Uri uri1 = Uri.create("http://stackoverflow.com:8443/questions/17814461/jacoco-maven-testng-0-test-coverage");
    Uri uri2 = Uri.create("ws://stackoverflow.com:8443/questions/1057564/pretty-git-branch-graphs");
    assertFalse(HttpUtils.isSameBase(uri1, uri2), "Base URLs should be different, but true was returned from isSameBase");
  }

  @Test
  public void testIsSameBaseUrlReturnsFalseWhenHostDifferent() {
    Uri uri1 = Uri.create("http://stackoverflow.com:8443/questions/17814461/jacoco-maven-testng-0-test-coverage");
    Uri uri2 = Uri.create("http://example.com:8443/questions/1057564/pretty-git-branch-graphs");
    assertFalse(HttpUtils.isSameBase(uri1, uri2), "Base URLs should be different, but true was returned from isSameBase");
  }

  @Test
  public void testGetPathWhenPathIsNonEmpty() {
    Uri uri = Uri.create("http://stackoverflow.com:8443/questions/17814461/jacoco-maven-testng-0-test-coverage");
    String path = HttpUtils.getNonEmptyPath(uri);
    assertEquals(path, "/questions/17814461/jacoco-maven-testng-0-test-coverage", "Incorrect path returned from getNonEmptyPath");
  }

  @Test
  public void testGetPathWhenPathIsEmpty() {
    Uri uri = Uri.create("http://stackoverflow.com");
    String path = HttpUtils.getNonEmptyPath(uri);
    assertEquals(path, "/", "Incorrect path returned from getNonEmptyPath");
  }

  @Test
  public void testIsSameBaseUrlReturnsTrueWhenOneUriHasDefaultPort() {
    Uri uri1 = Uri.create("http://stackoverflow.com:80/questions/17814461/jacoco-maven-testng-0-test-coverage");
    Uri uri2 = Uri.create("http://stackoverflow.com/questions/1057564/pretty-git-branch-graphs");
    assertTrue(HttpUtils.isSameBase(uri1, uri2), "Base URLs should be same, but false was returned from isSameBase");
  }

  @Test
  public void testExtractCharsetWithoutQuotes() {
    Charset charset = HttpUtils.extractCharset("text/html; charset=iso-8859-1");
    assertEquals(charset, ISO_8859_1);
  }

  @Test
  public void testExtractCharsetWithSingleQuotes() {
    Charset charset = HttpUtils.extractCharset("text/html; charset='iso-8859-1'");
    assertEquals(charset, ISO_8859_1);
  }

  @Test
  public void testExtractCharsetWithDoubleQuotes() {
    Charset charset = HttpUtils.extractCharset("text/html; charset=\"iso-8859-1\"");
    assertEquals(charset, ISO_8859_1);
  }

  @Test
  public void testExtractCharsetWithDoubleQuotesAndSpaces() {
    Charset charset = HttpUtils.extractCharset("text/html; charset= \"iso-8859-1\" ");
    assertEquals(charset, ISO_8859_1);
  }

  @Test
  public void testExtractCharsetFallsBackToUtf8() {
    Charset charset = HttpUtils.extractCharset(APPLICATION_JSON.toString());
    assertNull(charset);
  }

  @Test
  public void testGetHostHeaderNoVirtualHost() {
    Request request = Dsl.get("http://stackoverflow.com/questions/1057564/pretty-git-branch-graphs").build();
    Uri uri = Uri.create("http://stackoverflow.com/questions/1057564/pretty-git-branch-graphs");
    String hostHeader = HttpUtils.hostHeader(request, uri);
    assertEquals(hostHeader, "stackoverflow.com", "Incorrect hostHeader returned");
  }

  @Test
  public void testGetHostHeaderHasVirtualHost() {
    Request request = Dsl.get("http://stackoverflow.com/questions/1057564").setVirtualHost("example.com").build();
    Uri uri = Uri.create("http://stackoverflow.com/questions/1057564/pretty-git-branch-graphs");
    String hostHeader = HttpUtils.hostHeader(request, uri);
    assertEquals(hostHeader, "example.com", "Incorrect hostHeader returned");
  }

  @Test
  public void testDefaultFollowRedirect() {
    Request request = Dsl.get("http://stackoverflow.com/questions/1057564").setVirtualHost("example.com").build();
    DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder().build();
    boolean followRedirect = HttpUtils.followRedirect(config, request);
    assertFalse(followRedirect, "Default value of redirect should be false");
  }

  @Test
  public void testGetFollowRedirectInRequest() {
    Request request = Dsl.get("http://stackoverflow.com/questions/1057564").setFollowRedirect(true).build();
    DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder().build();
    boolean followRedirect = HttpUtils.followRedirect(config, request);
    assertTrue(followRedirect, "Follow redirect must be true as set in the request");
  }

  @Test
  public void testGetFollowRedirectInConfig() {
    Request request = Dsl.get("http://stackoverflow.com/questions/1057564").build();
    DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder().setFollowRedirect(true).build();
    boolean followRedirect = HttpUtils.followRedirect(config, request);
    assertTrue(followRedirect, "Follow redirect should be equal to value specified in config when not specified in request");
  }

  @Test
  public void testGetFollowRedirectPriorityGivenToRequest() {
    Request request = Dsl.get("http://stackoverflow.com/questions/1057564").setFollowRedirect(false).build();
    DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder().setFollowRedirect(true).build();
    boolean followRedirect = HttpUtils.followRedirect(config, request);
    assertFalse(followRedirect, "Follow redirect value set in request should be given priority");
  }

  private void formUrlEncoding(Charset charset) throws Exception {
    String key = "key";
    String value = "中文";
    List<Param> params = new ArrayList<>();
    params.add(new Param(key, value));
    ByteBuffer ahcBytes = HttpUtils.urlEncodeFormParams(params, charset);
    String ahcString = toUsAsciiString(ahcBytes);
    String jdkString = key + "=" + URLEncoder.encode(value, charset.name());
    assertEquals(ahcString, jdkString);
  }

  @Test
  public void formUrlEncodingShouldSupportUtf8Charset() throws Exception {
    formUrlEncoding(UTF_8);
  }

  @Test
  public void formUrlEncodingShouldSupportNonUtf8Charset() throws Exception {
    formUrlEncoding(Charset.forName("GBK"));
  }

  @Test
  public void computeOriginForPlainUriWithImplicitPort() {
    assertEquals(HttpUtils.computeOriginHeader(Uri.create("ws://foo.com/bar")), "http://foo.com");
  }

  @Test
  public void computeOriginForPlainUriWithDefaultPort() {
    assertEquals(HttpUtils.computeOriginHeader(Uri.create("ws://foo.com:80/bar")), "http://foo.com");
  }

  @Test
  public void computeOriginForPlainUriWithNonDefaultPort() {
    assertEquals(HttpUtils.computeOriginHeader(Uri.create("ws://foo.com:81/bar")), "http://foo.com:81");
  }

  @Test
  public void computeOriginForSecuredUriWithImplicitPort() {
    assertEquals(HttpUtils.computeOriginHeader(Uri.create("wss://foo.com/bar")), "https://foo.com");
  }

  @Test
  public void computeOriginForSecuredUriWithDefaultPort() {
    assertEquals(HttpUtils.computeOriginHeader(Uri.create("wss://foo.com:443/bar")), "https://foo.com");
  }

  @Test
  public void computeOriginForSecuredUriWithNonDefaultPort() {
    assertEquals(HttpUtils.computeOriginHeader(Uri.create("wss://foo.com:444/bar")), "https://foo.com:444");
  }
}
