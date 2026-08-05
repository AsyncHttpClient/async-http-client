/*
 * Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.netty.handler.codec.compression.DecompressionException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Verifies that HTTP/1.1 automatic response decompression honours the configured decompressed-size ceiling
 * ({@link AsyncHttpClientConfig#getMaxDecompressedResponseSize()}), so a highly compressible body cannot
 * inflate without bound (a "decompression bomb"). Before the fix both {@code HttpContentDecompressor}
 * construction sites used the no-arg constructor (maxAllocation=0 = unbounded). See
 * {@code ChannelManager#newHttpContentDecompressor}.
 */
public class Http1DecompressionLimitTest {

  // 4 MiB of a single repeated byte: gzips to a few KiB but inflates to 4 MiB.
  private static final byte[] LARGE_PAYLOAD = new byte[4 * 1024 * 1024];

  static {
    Arrays.fill(LARGE_PAYLOAD, (byte) 'a');
  }

  private static HttpServer httpServer;

  @BeforeClass
  public static void setupServer() throws Exception {
    httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    httpServer.createContext("/gzip-bomb").setHandler(new HttpHandler() {
      @Override
      public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Encoding", "gzip");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        try {
          GZIPOutputStream gzip = new GZIPOutputStream(out);
          gzip.write(LARGE_PAYLOAD);
          gzip.finish();
          gzip.close();
        } finally {
          out.close();
        }
      }
    });
    httpServer.start();
  }

  @AfterClass
  public static void stopServer() {
    if (httpServer != null) {
      httpServer.stop(0);
    }
  }

  private static AsyncHttpClient clientWithLimit(int maxDecompressedResponseSize) {
    return clientWithLimit(maxDecompressedResponseSize, false);
  }

  private static AsyncHttpClient clientWithLimit(int maxDecompressedResponseSize, boolean keepEncodingHeader) {
    AsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
            .setCompressionEnforced(true)
            .setKeepEncodingHeader(keepEncodingHeader)
            .setMaxDecompressedResponseSize(maxDecompressedResponseSize)
            .build();
    return new DefaultAsyncHttpClient(config);
  }

  private static String url() {
    return "http://localhost:" + httpServer.getAddress().getPort() + "/gzip-bomb";
  }

  private static boolean hasDecompressionCause(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof DecompressionException) {
        return true;
      }
    }
    return false;
  }

  @Test
  public void decompressionBeyondLimitFails() throws Exception {
    // 256 KiB ceiling, but the body inflates to 4 MiB -> Netty must abort with a DecompressionException
    // rather than allocating the full 4 MiB.
    try (AsyncHttpClient client = clientWithLimit(256 * 1024)) {
      try {
        client.prepareGet(url()).execute().get(30, TimeUnit.SECONDS);
        fail("a body inflating past the configured ceiling must not be delivered");
      } catch (ExecutionException ex) {
        assertTrue(hasDecompressionCause(ex),
                "expected a Netty DecompressionException in the cause chain but got: " + ex.getCause());
      }
    }
  }

  @Test
  public void decompressionWithinLimitSucceeds() throws Exception {
    // A generous ceiling comfortably above the 4 MiB inflated size lets the same body through unchanged.
    try (AsyncHttpClient client = clientWithLimit(64 * 1024 * 1024)) {
      Response response = client.prepareGet(url()).execute().get(30, TimeUnit.SECONDS);
      assertEquals(response.getStatusCode(), 200);
      assertEquals(response.getResponseBodyAsBytes().length, LARGE_PAYLOAD.length);
    }
  }

  @Test
  public void decompressionBeyondLimitFailsWithKeepEncodingHeader() throws Exception {
    // keepEncodingHeader selects the anonymous HttpContentDecompressor subclass, a separate construction
    // site that has to forward the ceiling to super. Covers the half-fix where only the plain branch is
    // bounded.
    try (AsyncHttpClient client = clientWithLimit(256 * 1024, true)) {
      try {
        client.prepareGet(url()).execute().get(30, TimeUnit.SECONDS);
        fail("the keepEncodingHeader decompressor must honour the ceiling too");
      } catch (ExecutionException ex) {
        assertTrue(hasDecompressionCause(ex),
                "expected a Netty DecompressionException in the cause chain but got: " + ex.getCause());
      }
    }
  }

  @Test
  public void zeroDisablesTheLimit() throws Exception {
    // 0 keeps Netty's unbounded behaviour for callers that deliberately opt out.
    try (AsyncHttpClient client = clientWithLimit(0)) {
      Response response = client.prepareGet(url()).execute().get(30, TimeUnit.SECONDS);
      assertEquals(response.getStatusCode(), 200);
      assertEquals(response.getResponseBodyAsBytes().length, LARGE_PAYLOAD.length);
    }
  }
}
