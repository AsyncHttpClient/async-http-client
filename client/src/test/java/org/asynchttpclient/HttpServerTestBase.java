package org.asynchttpclient;

import java.util.HashMap;
import java.util.Map;

import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeSuite;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

public class HttpServerTestBase extends AbstractBasicTest {
    protected Map<String, ServerDetails> mockServers = new HashMap<>();

    final private static int DEFAULT_PORT = 8080;
    final private static WireMockServer wiremockServer = new WireMockServer(wireMockConfig().port(DEFAULT_PORT));

    @BeforeSuite
    public void init() {
        mockServers.put("www.lemonde.fr", new ServerDetails("www.lemonde.fr"));
        mockServers.put("test.s3.amazonaws.com", new ServerDetails("test.s3.amazonaws.com"));
        mockServers.put("www.cyberpresse.ca", new ServerDetails("www.cyberpresse.ca"));
        mockServers.put("mail.google.com", new ServerDetails("mail.google.com"));
        mockServers.put("www.google.com", new ServerDetails("www.google.com"));
        mockServers.put("www.microsoft.com", new ServerDetails("www.microsoft.com"));
        mockServers.put("www.apache.org", new ServerDetails("www.apache.org"));
        mockServers.put("google.com", new ServerDetails("google.com"));
        mockServers.put("www.sun.com", new ServerDetails("www.sun.com"));
    }
    
    @BeforeSuite
    public void startWiremockServer() throws Exception {
        wiremockServer.start();
    }

    @AfterSuite
    public void stopWiremockServer() throws Exception {
        wiremockServer.stop();
    }

    public static class ServerDetails {
        final private String website;

        public ServerDetails(String website) {
            this.website = website;
        }

        public String getWebsite() {
            return website;
        }

        public String getMockUrl() {
            return "http://localhost:" + DEFAULT_PORT + "/" + website;
        }

        public String getMockRelativeUrl() {
            return "/" + website;
        }
    }
}
