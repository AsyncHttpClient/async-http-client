package org.asynchttpclient.spnego;

import org.apache.commons.io.FileUtils;
import org.apache.kerby.kerberos.kerb.server.SimpleKdcServer;
import org.asynchttpclient.AbstractBasicTest;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.security.auth.Subject;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
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

  @BeforeClass
  public static void startServers() throws Exception {
    basedir = System.getProperty("basedir");
    if (basedir == null) {
      basedir = new File(".").getCanonicalPath();
    }

    // System.setProperty("sun.security.krb5.debug", "true");
    System.setProperty("java.security.krb5.conf",
        new File(basedir + File.separator + "target" + File.separator + "krb5.conf").getCanonicalPath());

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

    aliceKeytab = new File(basedir + File.separator + "target" + File.separator + "alice.keytab");
    bobKeytab = new File(basedir + File.separator + "target" + File.separator + "bob.keytab");
    kerbyServer.exportPrincipal(alice, aliceKeytab);
    kerbyServer.exportPrincipal(bob, bobKeytab);

    kerbyServer.start();

    Map<String, String> loginConfig = new HashMap<>();
    loginConfig.put("useKeyTab", "true");
    loginConfig.put("refreshKrb5Config", "true");
    loginConfig.put("keyTab", bobKeytab.getCanonicalPath());
    loginConfig.put("principal", bob);
    loginConfig.put("debug", String.valueOf(true));

    LoginContext loginContext;
    loginContext = new LoginContext("", new Subject(), null, new Configuration() {
      @Override
      public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
        return new AppConfigurationEntry[] {
            new AppConfigurationEntry("com.sun.security.auth.module.Krb5LoginModule",
                AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                loginConfig)};
      }
    });
    loginContext.login();

    LoggerFactory.getLogger(SpnegoEngineTest.class).info("Able to log in as {} successfully. Kerby server is ready.", alice);

    loginContext.logout();
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
    SpnegoEngine spnegoEngine = new SpnegoEngine("bob",
        "service.ws.apache.org",
        false,
        loginConfig,
        null);

    String token = spnegoEngine.generateToken("localhost");
    Assert.assertNotNull(token);
    Assert.assertTrue(token.startsWith("YII"));
  }

  @AfterClass
  public static void cleanup() throws Exception {
    if (kerbyServer != null) {
      kerbyServer.stop();
    }
    FileUtils.deleteQuietly(aliceKeytab);
    FileUtils.deleteQuietly(bobKeytab);
  }
}
