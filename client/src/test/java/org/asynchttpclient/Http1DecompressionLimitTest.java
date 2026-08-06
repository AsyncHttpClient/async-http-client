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
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.handler.BoundedHttpContentDecompressor;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * HTTP/1.1 automatic response decompression must honour
 * {@link AsyncHttpClientConfig#getMaxDecompressedResponseSize()} <strong>cumulatively over the whole
 * response</strong>, so a small, highly compressible body cannot inflate without bound.
 *
 * <p>The distinction matters. Netty's {@code HttpContentDecompressor(int maxAllocation)} caps the output
 * of <em>one</em> {@code ZlibDecoder.decode()} call and keeps no counter across calls, and the HTTP codec
 * feeds the decompressor at most {@code httpClientCodecMaxChunkSize} (8 KiB) at a time. A body with a
 * merely ordinary compression ratio therefore sails straight through such a "limit" no matter how large
 * its total becomes - which is what {@link #cumulativeLimitFiresEvenWhenNoSingleChunkExceedsIt()} pins
 * down. Passing a non-zero maxAllocation is also unsafe: Netty 4.1 forwards it to
 * {@code new BrotliDecoder(int)}, an <em>input</em> buffer size that is eagerly allocated as native
 * memory, so a 256 MiB "ceiling" would mean a 256 MiB direct allocation per {@code br} response - see
 * {@link #decompressorIsBuiltWithoutANettyMaxAllocation()}.
 */
public class Http1DecompressionLimitTest {

  // 4 MiB of a single repeated byte: gzips to a few KiB but inflates to 4 MiB.
  private static final byte[] LARGE_PAYLOAD = new byte[4 * 1024 * 1024];

  /**
   * 8 MiB that gzips at a ratio of roughly 20:1. Deliberately NOT a run of one byte: at 20:1 the largest
   * amount a single 8 KiB codec chunk can inflate to is about 160 KiB, which is far below both the 1 MiB
   * ceiling this payload is served against and Netty's 256 MiB maxAllocation default. Only a counter that
   * spans the whole response can stop it.
   */
  private static final byte[] RATIO_20_PAYLOAD = ratio20Payload(8 * 1024 * 1024);

  private static final int LOW_RATIO_LIMIT = 1024 * 1024;

  static {
    Arrays.fill(LARGE_PAYLOAD, (byte) 'a');
  }

  private static byte[] ratio20Payload(int size) {
    byte[] bytes = new byte[size];
    Arrays.fill(bytes, (byte) 'a');
    Random random = new Random(42); // fixed seed: the compression ratio must not vary between runs
    for (int i = 0; i < size; i++) {
      if (random.nextInt(48) == 0) {
        bytes[i] = (byte) ('b' + random.nextInt(20));
      }
    }
    return bytes;
  }

  private static byte[] gzip(byte[] payload) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    GZIPOutputStream gzip = new GZIPOutputStream(out);
    gzip.write(payload);
    gzip.finish();
    gzip.close();
    return out.toByteArray();
  }

  private static HttpServer httpServer;

  @BeforeClass
  public static void setupServer() throws Exception {
    httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    httpServer.createContext("/gzip-bomb").setHandler(gzipHandler(LARGE_PAYLOAD));
    httpServer.createContext("/gzip-ratio-20").setHandler(gzipHandler(RATIO_20_PAYLOAD));
    httpServer.start();
  }

  private static HttpHandler gzipHandler(final byte[] payload) {
    return new HttpHandler() {
      @Override
      public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Encoding", "gzip");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        try {
          GZIPOutputStream gzip = new GZIPOutputStream(out);
          gzip.write(payload);
          gzip.finish();
          gzip.close();
        } finally {
          out.close();
        }
      }
    };
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

  private static String url(String path) {
    return "http://localhost:" + httpServer.getAddress().getPort() + path;
  }

  private static boolean hasDecompressionCause(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof DecompressionException) {
        return true;
      }
    }
    return false;
  }

  /**
   * The core of the fix. The body inflates to 8 MiB against a 1 MiB ceiling, but no single decode step
   * produces more than about 160 KiB, so nothing that looks only at one chunk - including Netty's
   * maxAllocation - can catch it. The arithmetic is asserted, not assumed, so the payload cannot silently
   * drift into being a trivially compressible one.
   */
  @Test
  public void cumulativeLimitFiresEvenWhenNoSingleChunkExceedsIt() throws Exception {
    byte[] compressed = gzip(RATIO_20_PAYLOAD);
    double ratio = (double) RATIO_20_PAYLOAD.length / compressed.length;
    long largestSingleChunkInflation = (long) Math.ceil(ratio * 8192);

    assertTrue(RATIO_20_PAYLOAD.length > LOW_RATIO_LIMIT,
            "the payload must exceed the ceiling in total, else the test proves nothing");
    assertTrue(largestSingleChunkInflation < LOW_RATIO_LIMIT,
            "no single 8 KiB codec chunk may reach the ceiling on its own, else a per-chunk limit would "
                    + "also pass this test; ratio=" + ratio + ", per-chunk=" + largestSingleChunkInflation);

    try (AsyncHttpClient client = clientWithLimit(LOW_RATIO_LIMIT)) {
      try {
        client.prepareGet(url("/gzip-ratio-20")).execute().get(30, TimeUnit.SECONDS);
        fail("a body inflating past the configured ceiling must not be delivered, even in small steps");
      } catch (ExecutionException ex) {
        assertTrue(hasDecompressionCause(ex),
                "expected a DecompressionException in the cause chain but got: " + ex.getCause());
      }
    }
  }

  @Test
  public void decompressionBeyondLimitFails() throws Exception {
    // 256 KiB ceiling, but the body inflates to 4 MiB.
    try (AsyncHttpClient client = clientWithLimit(256 * 1024)) {
      try {
        client.prepareGet(url("/gzip-bomb")).execute().get(30, TimeUnit.SECONDS);
        fail("a body inflating past the configured ceiling must not be delivered");
      } catch (ExecutionException ex) {
        assertTrue(hasDecompressionCause(ex),
                "expected a DecompressionException in the cause chain but got: " + ex.getCause());
      }
    }
  }

  @Test
  public void decompressionWithinLimitSucceeds() throws Exception {
    // A generous ceiling comfortably above the 4 MiB inflated size lets the same body through unchanged.
    try (AsyncHttpClient client = clientWithLimit(64 * 1024 * 1024)) {
      Response response = client.prepareGet(url("/gzip-bomb")).execute().get(30, TimeUnit.SECONDS);
      assertEquals(response.getStatusCode(), 200);
      assertEquals(response.getResponseBodyAsBytes().length, LARGE_PAYLOAD.length);
    }
  }

  /**
   * The counter must restart per response, so a client whose ceiling is above a single body's inflated
   * size can serve an unbounded number of them on a pooled connection.
   */
  @Test
  public void limitIsPerResponseNotPerConnection() throws Exception {
    try (AsyncHttpClient client = clientWithLimit(8 * 1024 * 1024)) {
      for (int i = 0; i < 3; i++) {
        Response response = client.prepareGet(url("/gzip-bomb")).execute().get(30, TimeUnit.SECONDS);
        assertEquals(response.getStatusCode(), 200);
        assertEquals(response.getResponseBodyAsBytes().length, LARGE_PAYLOAD.length,
                "response " + i + " must decompress fully; the counter must not carry over");
      }
    }
  }

  @Test
  public void decompressionBeyondLimitFailsWithKeepEncodingHeader() throws Exception {
    // keepEncodingHeader used to select a separate anonymous HttpContentDecompressor subclass; it is now a
    // constructor flag, and this covers the half-fix where only one of the two branches was bounded.
    try (AsyncHttpClient client = clientWithLimit(256 * 1024, true)) {
      try {
        client.prepareGet(url("/gzip-bomb")).execute().get(30, TimeUnit.SECONDS);
        fail("the keepEncodingHeader decompressor must honour the ceiling too");
      } catch (ExecutionException ex) {
        assertTrue(hasDecompressionCause(ex),
                "expected a DecompressionException in the cause chain but got: " + ex.getCause());
      }
    }
  }

  @Test
  public void keepEncodingHeaderStillKeepsTheEncodingHeader() throws Exception {
    try (AsyncHttpClient client = clientWithLimit(64 * 1024 * 1024, true)) {
      Response response = client.prepareGet(url("/gzip-bomb")).execute().get(30, TimeUnit.SECONDS);
      assertEquals(response.getStatusCode(), 200);
      assertEquals(response.getHeader("Content-Encoding"), "gzip",
              "keepEncodingHeader must leave the response advertising the encoding it arrived with");
      assertEquals(response.getResponseBodyAsBytes().length, LARGE_PAYLOAD.length);
    }
  }

  @Test
  public void zeroDisablesTheLimit() throws Exception {
    // 0 keeps the unbounded behaviour for callers that deliberately opt out.
    try (AsyncHttpClient client = clientWithLimit(0)) {
      Response response = client.prepareGet(url("/gzip-bomb")).execute().get(30, TimeUnit.SECONDS);
      assertEquals(response.getStatusCode(), 200);
      assertEquals(response.getResponseBodyAsBytes().length, LARGE_PAYLOAD.length);
    }
  }

  /**
   * brotli4j is not on the 2.x classpath, so a {@code br} response cannot be exercised end to end here.
   * What can be pinned - and is the part that matters - is that AHC never hands Netty a non-zero
   * maxAllocation. In Netty 4.1, {@code HttpContentDecompressor.newContentDecoder} passes maxAllocation to
   * {@code new BrotliDecoder(int)}, whose one-argument form is {@code inputBufferSize}, and
   * {@code BrotliDecoder.handlerAdded} eagerly allocates {@code new DecoderJNI.Wrapper(inputBufferSize)} in
   * direct memory. With maxAllocation left at 256 MiB, any peer that answers
   * {@code Content-Encoding: br} - even with an empty body - would cost the client 256 MiB of native
   * memory per concurrent response: cheaper for an attacker than the bomb this limit exists to stop.
   */
  @Test
  public void decompressorIsBuiltWithoutANettyMaxAllocation() throws Exception {
    AsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
            .setMaxDecompressedResponseSize(256 * 1024 * 1024)
            .build();
    Timer timer = new HashedWheelTimer();
    ChannelManager channelManager = new ChannelManager(config, timer);
    try {
      Method factory = ChannelManager.class.getDeclaredMethod("newHttpContentDecompressor");
      factory.setAccessible(true);
      HttpContentDecompressor decompressor = (HttpContentDecompressor) factory.invoke(channelManager);

      assertTrue(decompressor instanceof BoundedHttpContentDecompressor,
              "the pipeline must use the counting decompressor, not a bare Netty one: " + decompressor.getClass());

      Field maxAllocation = HttpContentDecompressor.class.getDeclaredField("maxAllocation");
      maxAllocation.setAccessible(true);
      assertEquals(maxAllocation.getInt(decompressor), 0,
              "Netty's maxAllocation must stay 0: it does not bound a response, and a non-zero value is "
                      + "used as the eagerly allocated BrotliDecoder input buffer size");
    } finally {
      channelManager.close();
      timer.stop();
    }
  }
}
