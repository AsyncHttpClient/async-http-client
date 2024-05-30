package org.asynchttpclient;

import com.aayushatharva.brotli4j.encoder.BrotliOutputStream;
import com.aayushatharva.brotli4j.encoder.Encoder;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.github.luben.zstd.Zstd;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AutomaticDecompressionTest {
  private static final String UNCOMPRESSED_PAYLOAD = "a".repeat(500);

  private static HttpServer HTTP_SERVER;

  private static AsyncHttpClient createClient() {
    AsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
        .setEnableAutomaticDecompression(true)
        .build();
    return new DefaultAsyncHttpClient(config);
  }

  @BeforeAll
  static void setupServer() throws Exception {
    HTTP_SERVER = HttpServer.create(new InetSocketAddress(0), 0);

    HTTP_SERVER.createContext("/br").setHandler(new HttpHandler() {
      @Override
      public void handle(HttpExchange exchange)
          throws IOException {
        exchange.getResponseHeaders().set("Content-Encoding", "br");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        Encoder.Parameters params = new Encoder.Parameters();
        BrotliOutputStream brotliOutputStream = new BrotliOutputStream(out, params);
        brotliOutputStream.write(UNCOMPRESSED_PAYLOAD.getBytes(StandardCharsets.UTF_8));
        brotliOutputStream.flush();
        brotliOutputStream.close();
      }
    });

    HTTP_SERVER.createContext("/zstd").setHandler(new HttpHandler() {
      @Override
      public void handle(HttpExchange exchange)
          throws IOException {
        exchange.getResponseHeaders().set("Content-Encoding", "zstd");
        byte[] compressedData = new byte[UNCOMPRESSED_PAYLOAD.length()];
        long n = Zstd.compress(compressedData, UNCOMPRESSED_PAYLOAD.getBytes(StandardCharsets.UTF_8), 2, true);
        exchange.sendResponseHeaders(200, n);
        OutputStream out = exchange.getResponseBody();
        out.write(compressedData, 0, (int) n);
        out.flush();
        out.close();
      }
    });

    HTTP_SERVER.createContext("/gzip").setHandler(new HttpHandler() {
      @Override
      public void handle(HttpExchange exchange)
          throws IOException {
        exchange.getResponseHeaders().set("Content-Encoding", "gzip");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        GZIPOutputStream gzip = new GZIPOutputStream(out);
        gzip.write(UNCOMPRESSED_PAYLOAD.getBytes(StandardCharsets.UTF_8));
        gzip.flush();
        gzip.close();
      }
    });

    HTTP_SERVER.start();
  }

  @AfterAll
  static void stopServer() {
    if (HTTP_SERVER != null) {
      HTTP_SERVER.stop(0);
    }
  }

  @Test
  void zstd() throws Throwable {
    io.netty.handler.codec.compression.Zstd.ensureAvailability();
    try (AsyncHttpClient client = createClient()) {
      Request request = new RequestBuilder("GET")
          .setUrl("http://localhost:" + HTTP_SERVER.getAddress().getPort() + "/zstd")
          .build();
      Response response = client.executeRequest(request).get();
      assertEquals(200, response.getStatusCode());
      assertEquals(UNCOMPRESSED_PAYLOAD, response.getResponseBody());
    }
  }

  @Test
  void brotli() throws Throwable {
    io.netty.handler.codec.compression.Brotli.ensureAvailability();
    try (AsyncHttpClient client = createClient()) {
      Request request = new RequestBuilder("GET")
          .setUrl("http://localhost:" + HTTP_SERVER.getAddress().getPort() + "/br")
          .build();
      Response response = client.executeRequest(request).get();
      assertEquals(200, response.getStatusCode());
      assertEquals(UNCOMPRESSED_PAYLOAD, response.getResponseBody());
    }
  }

  @Test
  void gzip() throws Throwable {
    try (AsyncHttpClient client = createClient()) {
      Request request = new RequestBuilder("GET")
          .setUrl("http://localhost:" + HTTP_SERVER.getAddress().getPort() + "/gzip")
          .build();
      Response response = client.executeRequest(request).get();
      assertEquals(200, response.getStatusCode());
      assertEquals(UNCOMPRESSED_PAYLOAD, response.getResponseBody());
    }
  }


}
