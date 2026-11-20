/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.netty.request;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.util.ReferenceCountUtil;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.RequestBuilder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.ACCEPT_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_ENCODING;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.asynchttpclient.netty.request.NettyRequestFactory.filterOutUndecodableAcceptEncodings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Brotli and zstd are both on the test classpath, so the availability flags are passed in, to the helper or to the
 * factory, rather than taken from Netty's {@code Brotli.isAvailable()} and {@code Zstd.isAvailable()}.
 */
public class NettyRequestFactoryAcceptEncodingTest {

    // Brotli and zstd availability; with both available the header is sent as the caller set it.
    private static final boolean[][] A_LIBRARY_MISSING = {{false, false}, {false, true}, {true, false}};

    @Test
    public void dropsBothWhenNeitherIsAvailable() {
        assertEquals(List.of("gzip, deflate"), filter(false, false, "gzip, deflate, br, zstd"));
        assertEquals(List.of("gzip, deflate"), filter(false, false, "gzip, deflate, zstd, br"));
    }

    @Test
    public void dropsOnlyTheUnavailableOne() {
        assertEquals(List.of("gzip, deflate, zstd"), filter(false, true, "gzip, deflate, br, zstd"));
        assertEquals(List.of("gzip, deflate, br"), filter(true, false, "gzip, deflate, br, zstd"));
    }

    @Test
    public void leavesHeaderUntouchedWhenBothAreAvailable() {
        assertEquals(List.of("gzip", "br, zstd"), filter(true, true, "gzip", "br, zstd"));
    }

    @Test
    public void leavesHeaderUntouchedWhenNothingIsDropped() {
        assertEquals(List.of("gzip", "deflate"), filter(false, false, "gzip", "deflate"));
    }

    @Test
    public void filtersEveryHeaderFieldAndKeepsTheOthers() {
        assertEquals(List.of("gzip, deflate"), filter(false, false, "gzip", "br", "deflate, zstd"));
    }

    @Test
    public void newRequestDropsTheUnavailableCodingsWithAutomaticDecompression() {
        assertEquals(List.of("gzip, deflate"), newRequest(true, false, false, "gzip, deflate, br, zstd"));
        assertEquals(List.of("gzip, deflate, zstd"), newRequest(true, false, true, "gzip, deflate, br, zstd"));
        assertEquals(List.of("gzip, deflate, br"), newRequest(true, true, false, "gzip, deflate, br, zstd"));
        assertEquals(List.of("gzip, deflate, br, zstd"), newRequest(true, true, true, "gzip, deflate, br, zstd"));
    }

    @Test
    public void newRequestDropsAWildcardWithAutomaticDecompression() {
        assertEquals(List.of("gzip, deflate"), newRequest(true, false, false, "br, *"));
        assertEquals(List.of("gzip"), newRequest(true, false, false, "gzip, *"));
        assertEquals(List.of("gzip"), newRequest(true, false, false, "gzip, br;q=0", "*"));
        assertEquals(List.of("gzip, deflate"), newRequest(true, false, true, "*"));
        assertEquals(List.of("zstd, gzip, deflate"), newRequest(true, false, true, "zstd, *"));
        assertEquals(List.of("gzip;q=0.5, deflate;q=0.5"), newRequest(true, true, false, "*;q=0.5"));
        assertEquals(List.of("br, *"), newRequest(true, true, true, "br, *"));
    }

    @Test
    public void newRequestLeavesTheHeaderToTheUserWithoutAutomaticDecompression() {
        assertEquals(List.of("gzip, deflate, br, zstd"), newRequest(false, false, false, "gzip, deflate, br, zstd"));
        assertEquals(List.of("br, *"), newRequest(false, false, false, "br, *"));
    }

    @Test
    public void nettyServerAnswersWithADecodedCodingThatIsNotRefused() {
        String[] acceptEncodings = {"*", "gzip, *", "br, *", "gzip, br, *", "gzip, br;q=0, *", "*, zstd;q=0.5",
                "gzip;q=0.5, *;q=0.1", "gzip, deflate, br, zstd", "br;q=0, *", "zstd;q=0, *",
                "x-br;q=0, ZSTD;q=0, *", "br;q=0, zstd;q=0, gzip;q=0, deflate;q=0, snappy;q=0, *, x*",
                "compress, x*, *", "gzip, x-zstd;q=0, br;q=0, *", "snappy;q=0, deflate, *"};
        for (boolean[] available : A_LIBRARY_MISSING) {
            boolean brotliAvailable = available[0];
            boolean zstdAvailable = available[1];
            List<String> decoded = new ArrayList<>(List.of("gzip", "deflate"));
            if (brotliAvailable) {
                decoded.add("br");
            }
            if (zstdAvailable) {
                decoded.add("zstd");
            }
            for (String acceptEncoding : acceptEncodings) {
                String sent = String.join(", ", newRequest(true, brotliAvailable, zstdAvailable, acceptEncoding));
                String coding = nettyServerCoding(sent);
                assertTrue(coding == null || (decoded.contains(coding) && !refuses(acceptEncoding, coding)),
                        acceptEncoding + " sent as " + sent + " got " + coding + " with br " + brotliAvailable
                                + ", zstd " + zstdAvailable);
            }
        }
    }

    @Test
    public void nettyServerStillPicksGzipForAWildcardNextToGzip() {
        // Http2ContentDecompressor decodes only gzip and deflate, so the "*" must not turn into br or zstd.
        for (boolean[] available : A_LIBRARY_MISSING) {
            for (String acceptEncoding : new String[]{"gzip, *", "zstd;q=0.1, x-snappy;q=0.2, gzip, *"}) {
                List<String> sent = newRequest(true, available[0], available[1], acceptEncoding);
                assertEquals("gzip", nettyServerCoding(String.join(", ", sent)), acceptEncoding + " sent as " + sent);
            }
        }
        assertEquals("gzip", nettyServerCoding(String.join(", ", newRequest(true, false, false, "gzip, br, *"))));
        assertEquals("gzip", nettyServerCoding(String.join(", ", newRequest(true, false, true, "gzip, br, *"))));
    }

    @Test
    public void newRequestOffersOnlyAvailableCodingsWhenCompressionIsEnforced() {
        assertEquals(List.of("gzip,deflate"), newRequest(true, false, false));
        assertEquals(List.of("gzip,deflate", "br"), newRequest(true, true, false));
        assertEquals(List.of("gzip,deflate", "zstd"), newRequest(true, false, true));
    }

    private static List<String> newRequest(boolean automaticDecompression, boolean brotliAvailable, boolean zstdAvailable,
                                           String... acceptEncodings) {
        AsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                .setEnableAutomaticDecompression(automaticDecompression)
                .setCompressionEnforced(true)
                .build();
        RequestBuilder request = new RequestBuilder("GET").setUrl("http://example.com/");
        for (String acceptEncoding : acceptEncodings) {
            request.addHeader(ACCEPT_ENCODING, acceptEncoding);
        }
        return new NettyRequestFactory(config, brotliAvailable, zstdAvailable)
                .newNettyRequest(request.build(), false, null, null, null)
                .getHttpRequest()
                .headers()
                .getAll(ACCEPT_ENCODING);
    }

    private static boolean refuses(String acceptEncoding, String coding) {
        for (String element : acceptEncoding.split(",")) {
            String[] parts = element.split(";");
            if (parts[0].trim().equalsIgnoreCase(coding)) {
                for (int i = 1; i < parts.length; i++) {
                    String[] parameter = parts[i].split("=", 2);
                    if (parameter.length == 2 && parameter[0].trim().equalsIgnoreCase("q")
                            && Float.parseFloat(parameter[1].trim()) == 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // A Netty server with every coding Netty offers, br and zstd included as both are on the test classpath.
    private static String nettyServerCoding(String acceptEncoding) {
        EmbeddedChannel server = new EmbeddedChannel(new HttpContentCompressor());
        try {
            FullHttpRequest request = new DefaultFullHttpRequest(HTTP_1_1, GET, "/");
            request.headers().set(ACCEPT_ENCODING, acceptEncoding);
            server.writeInbound(request);
            ReferenceCountUtil.release(server.readInbound());
            server.writeOutbound(new DefaultFullHttpResponse(HTTP_1_1, OK,
                    Unpooled.copiedBuffer("hello, hello, hello", US_ASCII)));
            HttpResponse response = server.readOutbound();
            String coding = response.headers().get(CONTENT_ENCODING);
            ReferenceCountUtil.release(response);
            return coding;
        } finally {
            server.finishAndReleaseAll();
        }
    }

    private static List<String> filter(boolean brotliAvailable, boolean zstdAvailable, String... acceptEncodings) {
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.add(ACCEPT_ENCODING, Arrays.asList(acceptEncodings));
        filterOutUndecodableAcceptEncodings(headers, brotliAvailable, zstdAvailable);
        return headers.getAll(ACCEPT_ENCODING);
    }
}
