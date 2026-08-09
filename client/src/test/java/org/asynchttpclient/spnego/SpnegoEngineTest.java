package org.asynchttpclient.spnego;

import org.apache.commons.io.FileUtils;
import org.apache.kerby.kerberos.kerb.server.SimpleKdcServer;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.util.AuthenticatorUtils;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class SpnegoEngineTest extends AbstractBasicTest {
  private static SimpleKdcServer kerbyServer;

  private static String basedir;
  private static String alice;
  private static String bob;
  private static File aliceKeytab;
  private static File bobKeytab;
  private static File loginConfig;

  @BeforeClass
  public static void startServers() throws Exception {
    basedir = System.getProperty("basedir");
    if (basedir == null) {
      basedir = new File(".").getCanonicalPath();
    }

    // System.setProperty("sun.security.krb5.debug", "true");
    System.setProperty("java.security.krb5.conf",
        new File(basedir + File.separator + "target" + File.separator + "krb5.conf").getCanonicalPath());
    loginConfig = new File(basedir + File.separator + "target" + File.separator + "kerberos.jaas");
    System.setProperty("java.security.auth.login.config", loginConfig.getCanonicalPath());

    kerbyServer = new SimpleKdcServer();

    kerbyServer.setKdcRealm("service.ws.apache.org");
    kerbyServer.setAllowUdp(false);
    kerbyServer.setWorkDir(new File(basedir, "target"));

    //kerbyServer.setInnerKdcImpl(new NettyKdcServerImpl(kerbyServer.getKdcSetting()));

    kerbyServer.init();

    // Create principals
    alice = "alice@service.ws.apache.org";
    bob = "bob/service.ws.apache.org@service.ws.apache.org";

    kerbyServer.createPrincipal(alice, "alice");
    kerbyServer.createPrincipal(bob, "bob");
    // Host based service principal for the origin, so a token can be obtained for HTTP@localhost and
    // only for that host. negotiateTokenTargetsTheOriginEvenBehindAProxy relies on there being no such
    // principal for the proxy host.
    kerbyServer.createPrincipal("HTTP/localhost@service.ws.apache.org", "httppwd");

    aliceKeytab = new File(basedir + File.separator + "target" + File.separator + "alice.keytab");
    bobKeytab = new File(basedir + File.separator + "target" + File.separator + "bob.keytab");
    kerbyServer.exportPrincipal(alice, aliceKeytab);
    kerbyServer.exportPrincipal(bob, bobKeytab);

    kerbyServer.start();

    FileUtils.copyInputStreamToFile(SpnegoEngine.class.getResourceAsStream("/kerberos.jaas"), loginConfig);
  }

  @Test
  public void testSpnegoGenerateTokenWithUsernamePassword() throws Exception {
    SpnegoEngine spnegoEngine = new SpnegoEngine("alice",
        "alice",
        "bob",
        "service.ws.apache.org",
        false,
        null,
        "alice",
        null);
    String token = spnegoEngine.generateToken("localhost");
    Assert.assertNotNull(token);
    Assert.assertTrue(token.startsWith("YII"));
  }

  /**
   * The origin realm's Negotiate token must be minted for the origin service even when a proxy is
   * configured. Building it against the proxy host produced a service ticket for the proxy's SPN: a
   * confused deputy where the origin's credential is delivered to, and only usable by, the proxy, while
   * origin authentication fails.
   * <p>
   * This drives the real {@code perConnectionAuthorizationHeader} rather than the host-selection helper,
   * so that restoring the proxy host in the product code makes it fail. The proxy host below has no
   * service principal in the KDC, so a token can only be produced if the origin was chosen. It lives in
   * this class because obtaining a token initialises the JVM's Kerberos configuration process-wide, and
   * this is the class that already stands up a KDC and installs a krb5.conf for exactly that.
   */
  @Test
  public void negotiateTokenTargetsTheOriginEvenBehindAProxy() throws Exception {
    Request request = new RequestBuilder("GET").setUrl("http://localhost:8080/resource").build();
    ProxyServer proxyServer = new ProxyServer.Builder("no-such-proxy.invalid", 3128).build();
    Realm realm = new Realm.Builder("alice", "alice")
        .setScheme(Realm.AuthScheme.KERBEROS)
        .setUsePreemptiveAuth(true)
        // No explicit service principal name on purpose: setting one makes the SPN a constant and the
        // host irrelevant, which is what made an earlier version of this test pass against both arms.
        // Left unset, the name is HTTP@<host>, so only the origin has a principal the KDC will issue for.
        .setRealmName("service.ws.apache.org")
        .setUseCanonicalHostname(false)
        .setLoginContextName("alice")
        .build();

    String header = AuthenticatorUtils.perConnectionAuthorizationHeader(request, proxyServer, realm);

    Assert.assertNotNull(header, "no Negotiate header was produced for the origin");
    Assert.assertTrue(header.startsWith("Negotiate YII"),
        "the origin credential must be minted for the origin service, not the proxy's SPN: " + header);
  }

  @Test(expectedExceptions = SpnegoEngineException.class)
  public void testSpnegoGenerateTokenWithUsernamePasswordFail() throws Exception {
    SpnegoEngine spnegoEngine = new SpnegoEngine("alice",
        "wrong password",
        "bob",
        "service.ws.apache.org",
        false,
        null,
        "alice",
        null);
    spnegoEngine.generateToken("localhost");
  }

  @Test
  public void testSpnegoGenerateTokenWithCustomLoginConfig() throws Exception {
    Map<String, String> loginConfig = new HashMap<>();
    loginConfig.put("useKeyTab", "true");
    loginConfig.put("storeKey", "true");
    loginConfig.put("refreshKrb5Config", "true");
    loginConfig.put("keyTab", aliceKeytab.getCanonicalPath());
    loginConfig.put("principal", alice);
    loginConfig.put("debug", String.valueOf(true));
    SpnegoEngine spnegoEngine = new SpnegoEngine(null,
        null,
        "bob",
        "service.ws.apache.org",
        false,
        loginConfig,
        null,
        null);

    String token = spnegoEngine.generateToken("localhost");
    Assert.assertNotNull(token);
    Assert.assertTrue(token.startsWith("YII"));
  }

  @Test
  public void testGetCompleteServicePrincipalName() throws Exception {
    {
      SpnegoEngine spnegoEngine = new SpnegoEngine(null,
          null,
          "bob",
          "service.ws.apache.org",
          false,
          null,
          null,
          null);
      Assert.assertEquals("bob@service.ws.apache.org", spnegoEngine.getCompleteServicePrincipalName("localhost"));
    }
    {
      SpnegoEngine spnegoEngine = new SpnegoEngine(null,
          null,
          null,
          "service.ws.apache.org",
          true,
          null,
          null,
          null);
      Assert.assertNotEquals("HTTP@localhost", spnegoEngine.getCompleteServicePrincipalName("localhost"));
      Assert.assertTrue(spnegoEngine.getCompleteServicePrincipalName("localhost").startsWith("HTTP@"));
    }
    {
      SpnegoEngine spnegoEngine = new SpnegoEngine(null,
          null,
          null,
          "service.ws.apache.org",
          false,
          null,
          null,
          null);
      Assert.assertEquals("HTTP@localhost", spnegoEngine.getCompleteServicePrincipalName("localhost"));
    }
  }

  @AfterClass
  public static void cleanup() throws Exception {
    if (kerbyServer != null) {
      kerbyServer.stop();
    }
    FileUtils.deleteQuietly(aliceKeytab);
    FileUtils.deleteQuietly(bobKeytab);
    FileUtils.deleteQuietly(loginConfig);
  }
}
