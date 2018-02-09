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
package org.asynchttpclient.test;

import io.netty.handler.codec.http.HttpHeaders;
import org.apache.commons.io.FileUtils;
import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Response;
import org.asynchttpclient.SslEngineFactory;
import org.asynchttpclient.netty.ssl.JsseSslEngineFactory;
import org.asynchttpclient.util.MessageDigestUtils;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.security.authentication.DigestAuthenticator;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public class TestUtils {

  public final static int TIMEOUT = 30;
  public static final String USER = "user";
  public static final String ADMIN = "admin";
  public static final String TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET = "text/html;charset=UTF-8";
  public static final String TEXT_HTML_CONTENT_TYPE_WITH_ISO_8859_1_CHARSET = "text/html;charset=ISO-8859-1";
  public static final File TMP_DIR = new File(System.getProperty("java.io.tmpdir"), "ahc-tests-" + UUID.randomUUID().toString().substring(0, 8));
  private static final byte[] PATTERN_BYTES = "FooBarBazQixFooBarBazQixFooBarBazQixFooBarBazQixFooBarBazQixFooBarBazQix".getBytes(Charset.forName("UTF-16"));
  public static final File LARGE_IMAGE_FILE;
  public static final byte[] LARGE_IMAGE_BYTES;
  public static final String LARGE_IMAGE_BYTES_MD5;
  public static final File SIMPLE_TEXT_FILE;
  public static final String SIMPLE_TEXT_FILE_STRING;
  private static final LoginService LOGIN_SERVICE = new HashLoginService("MyRealm", "src/test/resources/realm.properties");

  static {
    try {
      TMP_DIR.mkdirs();
      TMP_DIR.deleteOnExit();
      LARGE_IMAGE_FILE = resourceAsFile("300k.png");
      LARGE_IMAGE_BYTES = FileUtils.readFileToByteArray(LARGE_IMAGE_FILE);
      LARGE_IMAGE_BYTES_MD5 = TestUtils.md5(LARGE_IMAGE_BYTES);
      SIMPLE_TEXT_FILE = resourceAsFile("SimpleTextFile.txt");
      SIMPLE_TEXT_FILE_STRING = FileUtils.readFileToString(SIMPLE_TEXT_FILE, UTF_8);
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  public static synchronized int findFreePort() throws IOException {
    try (ServerSocket socket = ServerSocketFactory.getDefault().createServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  public static File resourceAsFile(String path) throws URISyntaxException, IOException {
    ClassLoader cl = TestUtils.class.getClassLoader();
    URI uri = cl.getResource(path).toURI();
    if (uri.isAbsolute() && !uri.isOpaque()) {
      return new File(uri);
    } else {
      File tmpFile = File.createTempFile("tmpfile-", ".data", TMP_DIR);
      tmpFile.deleteOnExit();
      try (InputStream is = cl.getResourceAsStream(path)) {
        FileUtils.copyInputStreamToFile(is, tmpFile);
        return tmpFile;
      }
    }
  }

  public static File createTempFile(int approxSize) throws IOException {
    long repeats = approxSize / TestUtils.PATTERN_BYTES.length + 1;
    File tmpFile = File.createTempFile("tmpfile-", ".data", TMP_DIR);
    tmpFile.deleteOnExit();
    try (OutputStream out = Files.newOutputStream(tmpFile.toPath())) {
      for (int i = 0; i < repeats; i++) {
        out.write(PATTERN_BYTES);
      }

      long expectedFileSize = PATTERN_BYTES.length * repeats;
      assertEquals(tmpFile.length(), expectedFileSize, "Invalid file length");

      return tmpFile;
    }
  }

  public static ServerConnector addHttpConnector(Server server) {
    ServerConnector connector = new ServerConnector(server);
    server.addConnector(connector);
    return connector;
  }

  public static ServerConnector addHttpsConnector(Server server) throws IOException, URISyntaxException {

    String keyStoreFile = resourceAsFile("ssltest-keystore.jks").getAbsolutePath();
    SslContextFactory sslContextFactory = new SslContextFactory(keyStoreFile);
    sslContextFactory.setKeyStorePassword("changeit");

    String trustStoreFile = resourceAsFile("ssltest-cacerts.jks").getAbsolutePath();
    sslContextFactory.setTrustStorePath(trustStoreFile);
    sslContextFactory.setTrustStorePassword("changeit");

    HttpConfiguration httpsConfig = new HttpConfiguration();
    httpsConfig.setSecureScheme("https");
    httpsConfig.addCustomizer(new SecureRequestCustomizer());

    ServerConnector connector = new ServerConnector(server, new SslConnectionFactory(sslContextFactory, "http/1.1"), new HttpConnectionFactory(httpsConfig));

    server.addConnector(connector);

    return connector;
  }

  public static void addBasicAuthHandler(Server server, Handler handler) {
    addAuthHandler(server, Constraint.__BASIC_AUTH, new BasicAuthenticator(), handler);
  }

  public static void addDigestAuthHandler(Server server, Handler handler) {
    addAuthHandler(server, Constraint.__DIGEST_AUTH, new DigestAuthenticator(), handler);
  }

  private static void addAuthHandler(Server server, String auth, LoginAuthenticator authenticator, Handler handler) {

    server.addBean(LOGIN_SERVICE);

    Constraint constraint = new Constraint();
    constraint.setName(auth);
    constraint.setRoles(new String[]{USER, ADMIN});
    constraint.setAuthenticate(true);

    ConstraintMapping mapping = new ConstraintMapping();
    mapping.setConstraint(constraint);
    mapping.setPathSpec("/*");

    Set<String> knownRoles = new HashSet<>();
    knownRoles.add(USER);
    knownRoles.add(ADMIN);

    List<ConstraintMapping> cm = new ArrayList<>();
    cm.add(mapping);

    ConstraintSecurityHandler security = new ConstraintSecurityHandler();
    security.setConstraintMappings(cm, knownRoles);
    security.setAuthenticator(authenticator);
    security.setLoginService(LOGIN_SERVICE);
    security.setHandler(handler);
    server.setHandler(security);
  }

  private static KeyManager[] createKeyManagers() throws GeneralSecurityException, IOException {
    KeyStore ks = KeyStore.getInstance("JKS");
    try (InputStream keyStoreStream = TestUtils.class.getClassLoader().getResourceAsStream("ssltest-cacerts.jks")) {
      char[] keyStorePassword = "changeit".toCharArray();
      ks.load(keyStoreStream, keyStorePassword);
    }
    assert (ks.size() > 0);

    // Set up key manager factory to use our key store
    char[] certificatePassword = "changeit".toCharArray();
    KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
    kmf.init(ks, certificatePassword);

    // Initialize the SSLContext to work with our key managers.
    return kmf.getKeyManagers();
  }

  private static TrustManager[] createTrustManagers() throws GeneralSecurityException, IOException {
    KeyStore ks = KeyStore.getInstance("JKS");
    try (InputStream keyStoreStream = TestUtils.class.getClassLoader().getResourceAsStream("ssltest-keystore.jks")) {
      char[] keyStorePassword = "changeit".toCharArray();
      ks.load(keyStoreStream, keyStorePassword);
    }
    assert (ks.size() > 0);

    TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(ks);
    return tmf.getTrustManagers();
  }

  public static SslEngineFactory createSslEngineFactory() {
    return createSslEngineFactory(new AtomicBoolean(true));
  }

  public static SslEngineFactory createSslEngineFactory(AtomicBoolean trust) {

    try {
      KeyManager[] keyManagers = createKeyManagers();
      TrustManager[] trustManagers = new TrustManager[]{dummyTrustManager(trust, (X509TrustManager) createTrustManagers()[0])};
      SecureRandom secureRandom = new SecureRandom();

      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(keyManagers, trustManagers, secureRandom);

      return new JsseSslEngineFactory(sslContext);

    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static TrustManager dummyTrustManager(final AtomicBoolean trust, final X509TrustManager tm) {
    return new DummyTrustManager(trust, tm);

  }

  public static File getClasspathFile(String file) throws FileNotFoundException {
    ClassLoader cl = null;
    try {
      cl = Thread.currentThread().getContextClassLoader();
    } catch (Throwable ex) {
      //
    }
    if (cl == null) {
      cl = TestUtils.class.getClassLoader();
    }
    URL resourceUrl = cl.getResource(file);

    try {
      return new File(new URI(resourceUrl.toString()).getSchemeSpecificPart());
    } catch (URISyntaxException e) {
      throw new FileNotFoundException(file);
    }
  }

  public static void assertContentTypesEquals(String actual, String expected) {
    assertEquals(actual.replace("; ", "").toLowerCase(Locale.ENGLISH), expected.replace("; ", "").toLowerCase(Locale.ENGLISH), "Unexpected content-type");
  }

  public static void writeResponseBody(HttpServletResponse response, String body) {
    response.setContentLength(body.length());
    try {
      response.getOutputStream().print(body);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static String md5(byte[] bytes) {
    return md5(bytes, 0, bytes.length);
  }

  public static String md5(byte[] bytes, int offset, int len) {
    try {
      MessageDigest md = MessageDigestUtils.pooledMd5MessageDigest();
      md.update(bytes, offset, len);
      return Base64.getEncoder().encodeToString(md.digest());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static class DummyTrustManager implements X509TrustManager {

    private final X509TrustManager tm;
    private final AtomicBoolean trust;

    DummyTrustManager(final AtomicBoolean trust, final X509TrustManager tm) {
      this.trust = trust;
      this.tm = tm;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
      tm.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
      if (!trust.get()) {
        throw new CertificateException("Server certificate not trusted.");
      }
      tm.checkServerTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return tm.getAcceptedIssuers();
    }
  }

  public static class AsyncCompletionHandlerAdapter extends AsyncCompletionHandler<Response> {

    @Override
    public Response onCompleted(Response response) throws Exception {
      return response;
    }

    @Override
    public void onThrowable(Throwable t) {
      fail("Unexpected exception: " + t.getMessage(), t);
    }
  }

  public static class AsyncHandlerAdapter implements AsyncHandler<String> {

    @Override
    public void onThrowable(Throwable t) {
      fail("Unexpected exception", t);
    }

    @Override
    public State onBodyPartReceived(final HttpResponseBodyPart content) throws Exception {
      return State.CONTINUE;
    }

    @Override
    public State onStatusReceived(final HttpResponseStatus responseStatus) {
      return State.CONTINUE;
    }

    @Override
    public State onHeadersReceived(final HttpHeaders headers) throws Exception {
      return State.CONTINUE;
    }

    @Override
    public String onCompleted() throws Exception {
      return "";
    }
  }
}
