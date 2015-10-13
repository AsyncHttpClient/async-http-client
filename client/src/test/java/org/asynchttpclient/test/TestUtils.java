package org.asynchttpclient.test;

import static java.nio.charset.StandardCharsets.*;
import static org.testng.Assert.assertEquals;

import org.apache.commons.io.FileUtils;
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
import org.reactivestreams.Publisher;

import rx.Observable;
import rx.RxReactiveStreams;

import javax.net.ssl.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class TestUtils {

    public static final String USER = "user";
    public static final String ADMIN = "admin";
    public static final String TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET = "text/html; charset=UTF-8";
    public static final String TEXT_HTML_CONTENT_TYPE_WITH_ISO_8859_1_CHARSET = "text/html; charset=ISO-8859-1";
    private static final File TMP_DIR = new File(System.getProperty("java.io.tmpdir"), "ahc-tests-" + UUID.randomUUID().toString().substring(0, 8));
    public static final byte[] PATTERN_BYTES = "FooBarBazQixFooBarBazQixFooBarBazQixFooBarBazQixFooBarBazQixFooBarBazQix".getBytes(Charset.forName("UTF-16"));
    public static final File LARGE_IMAGE_FILE;
    public static final byte[] LARGE_IMAGE_BYTES;
    public static final Publisher<ByteBuffer> LARGE_IMAGE_PUBLISHER;
    public static final File SIMPLE_TEXT_FILE;
    public static final String SIMPLE_TEXT_FILE_STRING;
    private static final LoginService LOGIN_SERVICE = new HashLoginService("MyRealm", "src/test/resources/realm.properties");

    static {
        try {
            TMP_DIR.mkdirs();
            TMP_DIR.deleteOnExit();
            LARGE_IMAGE_FILE = new File(TestUtils.class.getClassLoader().getResource("300k.png").toURI());
            LARGE_IMAGE_BYTES = FileUtils.readFileToByteArray(LARGE_IMAGE_FILE);
            LARGE_IMAGE_PUBLISHER = createPublisher(LARGE_IMAGE_BYTES, /*chunkSize*/ 1000);
            SIMPLE_TEXT_FILE = new File(TestUtils.class.getClassLoader().getResource("SimpleTextFile.txt").toURI());
            SIMPLE_TEXT_FILE_STRING = FileUtils.readFileToString(SIMPLE_TEXT_FILE, UTF_8);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static synchronized int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    public static File createTempFile(int approxSize) throws IOException {
        long repeats = approxSize / TestUtils.PATTERN_BYTES.length + 1;
        File tmpFile = File.createTempFile("tmpfile-", ".data", TMP_DIR);
        tmpFile.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(tmpFile)) {
            for (int i = 0; i < repeats; i++) {
                out.write(PATTERN_BYTES);
            }

            long expectedFileSize = PATTERN_BYTES.length * repeats;
            assertEquals(tmpFile.length(), expectedFileSize, "Invalid file length");

            return tmpFile;
        }
    }

    public static Publisher<ByteBuffer> createPublisher(final byte[] bytes, final int chunkSize) {
        Observable<ByteBuffer> observable = Observable.from(new ByteBufferIterable(bytes, chunkSize));
        return RxReactiveStreams.toPublisher(observable);
    }

    public static class ByteBufferIterable implements Iterable<ByteBuffer> {
        private final byte[] payload;
        private final int chunkSize;

        public ByteBufferIterable(byte[] payload, int chunkSize) {
            this.payload = payload;
            this.chunkSize = chunkSize;
        }


        @Override
        public Iterator<ByteBuffer> iterator() {
            return new Iterator<ByteBuffer>() {
                private int currentIndex = 0;
                @Override
                public boolean hasNext() {
                    return currentIndex != payload.length;
                }

                @Override
                public ByteBuffer next() {
                    int newIndex = Math.min(currentIndex + chunkSize, payload.length);
                    byte[] bytesInElement = Arrays.copyOfRange(payload, currentIndex, newIndex);
                    currentIndex = newIndex;
                    return ByteBuffer.wrap(bytesInElement);
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException("ByteBufferIterable's iterator does not support remove.");
                }
            };
        }
    }

    public static Server newJettyHttpServer(int port) {
        Server server = new Server();
        addHttpConnector(server, port);
        return server;
    }

    public static void addHttpConnector(Server server, int port) {
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);

        server.addConnector(connector);
    }

    public static Server newJettyHttpsServer(int port) throws URISyntaxException {
        Server server = new Server();
        addHttpsConnector(server, port);
        return server;
    }

    public static void addHttpsConnector(Server server, int port) throws URISyntaxException {
        ClassLoader cl = TestUtils.class.getClassLoader();

        URL keystoreUrl = cl.getResource("ssltest-keystore.jks");
        String keyStoreFile = new File(keystoreUrl.toURI()).getAbsolutePath();
        SslContextFactory sslContextFactory = new SslContextFactory(keyStoreFile);
        sslContextFactory.setKeyStorePassword("changeit");

        String trustStoreFile = new File(cl.getResource("ssltest-cacerts.jks").toURI()).getAbsolutePath();
        sslContextFactory.setTrustStorePath(trustStoreFile);
        sslContextFactory.setTrustStorePassword("changeit");

        HttpConfiguration httpsConfig = new HttpConfiguration();
        httpsConfig.setSecureScheme("https");
        httpsConfig.setSecurePort(port);
        httpsConfig.addCustomizer(new SecureRequestCustomizer());

        ServerConnector connector = new ServerConnector(server, new SslConnectionFactory(sslContextFactory, "http/1.1"), new HttpConnectionFactory(httpsConfig));
        connector.setPort(port);

        server.addConnector(connector);
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
        constraint.setRoles(new String[] { USER, ADMIN });
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
        InputStream keyStoreStream = Thread.currentThread().getContextClassLoader().getResourceAsStream("ssltest-cacerts.jks");
        char[] keyStorePassword = "changeit".toCharArray();
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(keyStoreStream, keyStorePassword);
        assert(ks.size() > 0);

        // Set up key manager factory to use our key store
        char[] certificatePassword = "changeit".toCharArray();
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(ks, certificatePassword);

        // Initialize the SSLContext to work with our key managers.
        return kmf.getKeyManagers();
    }

    private static TrustManager[] createTrustManagers() throws GeneralSecurityException, IOException {
        InputStream keyStoreStream = Thread.currentThread().getContextClassLoader().getResourceAsStream("ssltest-keystore.jks");
        char[] keyStorePassword = "changeit".toCharArray();
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(keyStoreStream, keyStorePassword);
        assert(ks.size() > 0);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        return tmf.getTrustManagers();
    }

    public static SSLContext createSslContext(AtomicBoolean trust) {
        try {
            KeyManager[] keyManagers = createKeyManagers();
            TrustManager[] trustManagers = new TrustManager[] { dummyTrustManager(trust, (X509TrustManager) createTrustManagers()[0]) };
            SecureRandom secureRandom = new SecureRandom();

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagers, trustManagers, secureRandom);

            return sslContext;
        } catch (Exception e) {
            throw new Error("Failed to initialize the server-side SSLContext", e);
        }
    }

    public static class DummyTrustManager implements X509TrustManager {

        private final X509TrustManager tm;
        private final AtomicBoolean trust;

        public DummyTrustManager(final AtomicBoolean trust, final X509TrustManager tm) {
            this.trust = trust;
            this.tm = tm;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            tm.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
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

    private static TrustManager dummyTrustManager(final AtomicBoolean trust, final X509TrustManager tm) {
        return new DummyTrustManager(trust, tm);

    }

    public static File getClasspathFile(String file) throws FileNotFoundException {
        ClassLoader cl = null;
        try {
            cl = Thread.currentThread().getContextClassLoader();
        } catch (Throwable ex) {
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
}
