package org.asynchttpclient.spnego;

import io.github.artsok.RepeatedIfExceptionsTest;
import org.apache.commons.io.FileUtils;
import org.apache.kerby.kerberos.kerb.server.SimpleKdcServer;
import org.asynchttpclient.AbstractBasicTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SpnegoEngineTest extends AbstractBasicTest {
    private SimpleKdcServer kerbyServer;

    private String basedir;
    private String alice;
    private String bob;
    private File aliceKeytab;
    private File bobKeytab;
    private File loginConfig;

    @BeforeEach
    public void startServers() throws Exception {
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

        aliceKeytab = new File(basedir + File.separator + "target" + File.separator + "alice.keytab");
        bobKeytab = new File(basedir + File.separator + "target" + File.separator + "bob.keytab");
        kerbyServer.exportPrincipal(alice, aliceKeytab);
        kerbyServer.exportPrincipal(bob, bobKeytab);

        kerbyServer.start();

        FileUtils.copyInputStreamToFile(SpnegoEngine.class.getResourceAsStream("/kerberos.jaas"), loginConfig);
    }

    @RepeatedIfExceptionsTest(repeats = 5)
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
        assertNotNull(token);
        assertTrue(token.startsWith("YII"));
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testSpnegoGenerateTokenWithUsernamePasswordFail() throws Exception {
        SpnegoEngine spnegoEngine = new SpnegoEngine("alice",
                "wrong password",
                "bob",
                "service.ws.apache.org",
                false,
                null,
                "alice",
                null);
        assertThrows(SpnegoEngineException.class, () -> spnegoEngine.generateToken("localhost"));
    }

    @RepeatedIfExceptionsTest(repeats = 5)
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
        assertNotNull(token);
        assertTrue(token.startsWith("YII"));
    }

    @RepeatedIfExceptionsTest(repeats = 5)
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
            assertEquals("bob@service.ws.apache.org", spnegoEngine.getCompleteServicePrincipalName("localhost"));
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
            assertTrue(spnegoEngine.getCompleteServicePrincipalName("localhost").startsWith("HTTP@"));
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
            assertEquals("HTTP@localhost", spnegoEngine.getCompleteServicePrincipalName("localhost"));
        }
    }

    @AfterEach
    public void cleanup() throws Exception {
        if (kerbyServer != null) {
            kerbyServer.stop();
        }
        FileUtils.deleteQuietly(aliceKeytab);
        FileUtils.deleteQuietly(bobKeytab);
        FileUtils.deleteQuietly(loginConfig);
    }
}
