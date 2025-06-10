package org.asynchttpclient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class StripAuthorizationOnRedirectHttpTest {
    private static HttpServer server;
    private static int port;
    private static volatile String lastAuthHeader;

    @BeforeAll
    public static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/redirect", new RedirectHandler());
        server.createContext("/final", new FinalHandler());
        server.start();
    }

    @AfterAll
    public static void stopServer() {
        server.stop(0);
    }

    static class RedirectHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            lastAuthHeader = auth;
            exchange.getResponseHeaders().add("Location", "http://localhost:" + port + "/final");
            try {
                exchange.sendResponseHeaders(302, -1);
            } catch (Exception ignored) {
            }
            exchange.close();
        }
    }

    static class FinalHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            lastAuthHeader = auth;
            try {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().close();
            } catch (Exception ignored) {
            }
            exchange.close();
        }
    }

    @Test
    void testAuthHeaderPropagatedByDefault() throws Exception {
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setFollowRedirect(true)
                .build();
        try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
            lastAuthHeader = null;
            client.prepareGet("http://localhost:" + port + "/redirect")
                    .setHeader("Authorization", "Bearer testtoken")
                    .execute()
                    .get(5, TimeUnit.SECONDS);
            // By default, Authorization header is propagated to /final
            assertEquals("Bearer testtoken", lastAuthHeader, "Authorization header should be present on redirect by default");
        }
    }

    @Test
    void testAuthHeaderStrippedWhenEnabled() throws Exception {
        DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setFollowRedirect(true)
                .setStripAuthorizationOnRedirect(true)
                .build();
        try (DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(config)) {
            lastAuthHeader = null;
            client.prepareGet("http://localhost:" + port + "/redirect")
                    .setHeader("Authorization", "Bearer testtoken")
                    .execute()
                    .get(5, TimeUnit.SECONDS);
            // When enabled, Authorization header should be stripped on /final
            assertNull(lastAuthHeader, "Authorization header should be stripped on redirect when enabled");
        }
    }
}
