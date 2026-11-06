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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpVersion;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.request.body.generator.InputStreamBodyGenerator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.USER_AGENT;
import static org.asynchttpclient.config.AsyncHttpClientConfigDefaults.defaultUserAgent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The {@code User-Agent} and {@code Content-Length} values the factory adds itself must reach the wire with the
 * same bytes as when they were set as a {@code String} or a boxed number, and the request headers must still
 * reject an illegal configured {@code User-Agent}.
 */
public class NettyRequestFactoryHeaderValuesTest {

    private static AsyncHttpClientConfig config(String userAgent) {
        return new DefaultAsyncHttpClientConfig.Builder().setUserAgent(userAgent).build();
    }

    private static List<String> wireValues(AsyncHttpClientConfig config, Request request, CharSequence name) {
        NettyRequest nettyRequest = new NettyRequestFactory(config).newNettyRequest(request, false, null, null, null);
        return wireValues(nettyRequest.getHttpRequest(), name);
    }

    /**
     * Encodes {@code request} and returns the raw bytes of every {@code name} field value, one char per byte.
     */
    private static List<String> wireValues(HttpRequest request, CharSequence name) {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestEncoder());
        StringBuilder wire = new StringBuilder();
        try {
            channel.writeOutbound(request);
            ByteBuf out;
            while ((out = channel.readOutbound()) != null) {
                try {
                    wire.append(out.toString(StandardCharsets.ISO_8859_1));
                } finally {
                    out.release();
                }
            }
        } finally {
            channel.finishAndReleaseAll();
        }

        String head = wire.substring(0, wire.indexOf("\r\n\r\n"));
        List<String> values = new ArrayList<>();
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(": ");
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name.toString())) {
                values.add(line.substring(colon + 2));
            }
        }
        return values;
    }

    /** What the encoder writes for {@code value} set as a plain {@code String}, as the factory used to set it. */
    private static List<String> stringWireValues(CharSequence name, String value) {
        HttpRequest reference = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/", Unpooled.EMPTY_BUFFER);
        reference.headers().set(name, value);
        return wireValues(reference, name);
    }

    @Test
    public void defaultUserAgentIsSentAsConfigured() {
        Request request = new RequestBuilder("GET").setUrl("http://example.com/").build();

        assertEquals(List.of(defaultUserAgent()),
                wireValues(new DefaultAsyncHttpClientConfig.Builder().build(), request, USER_AGENT));
    }

    @Test
    public void nonAsciiUserAgentKeepsTheBytesAStringValueWrites() {
        // Latin-1 chars go out as their own byte, anything above U+00FF as '?', U+010A included even though
        // its low byte is LF.
        String userAgent = "caf\u00e9 \u2713 \u010a \ud83d\ude00/1";
        Request request = new RequestBuilder("GET").setUrl("http://example.com/").build();

        List<String> sent = wireValues(config(userAgent), request, USER_AGENT);

        assertEquals(stringWireValues(USER_AGENT, userAgent), sent);
        assertEquals(List.of("caf\u00e9 ? ? ??/1"), sent);
    }

    @Test
    public void userAgentWithLineBreakIsStillRejectedOnEachRequest() {
        NettyRequestFactory factory = new NettyRequestFactory(config("AHC\r\nX-Injected: 1"));
        Request request = new RequestBuilder("GET").setUrl("http://example.com/").build();

        assertThrows(IllegalArgumentException.class, () -> factory.newNettyRequest(request, false, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> factory.newNettyRequest(request, false, null, null, null));
    }

    @Test
    public void callerUserAgentWinsOverTheConfiguredOne() {
        Request request = new RequestBuilder("GET").setUrl("http://example.com/").setHeader("User-Agent", "mine/1").build();

        assertEquals(List.of("mine/1"), wireValues(config("AHC/test"), request, USER_AGENT));
    }

    @Test
    public void bodylessPostPutAndPatchSendZeroContentLength() {
        for (String method : new String[]{"POST", "PUT", "PATCH"}) {
            Request request = new RequestBuilder(method).setUrl("http://example.com/").build();

            assertEquals(List.of("0"), wireValues(config("AHC/test"), request, CONTENT_LENGTH), method);
        }
    }

    @Test
    public void bodylessGetSendsNoContentLength() {
        Request request = new RequestBuilder("GET").setUrl("http://example.com/").build();

        assertEquals(List.of(), wireValues(config("AHC/test"), request, CONTENT_LENGTH));
    }

    @Test
    public void bodyContentLengthIsSentInDecimal() {
        for (int length : new int[]{0, 7, 1536, 1_000_000}) {
            Request request = new RequestBuilder("POST").setUrl("http://example.com/").setBody(new byte[length]).build();

            assertEquals(List.of(Integer.toString(length)), wireValues(config("AHC/test"), request, CONTENT_LENGTH));
        }
    }

    @Test
    public void contentLengthAboveIntRangeIsSentInFull() {
        long length = 5_000_000_000L;
        Request request = new RequestBuilder("PUT").setUrl("http://example.com/")
                .setBody(new InputStreamBodyGenerator(new ByteArrayInputStream(new byte[0]), length))
                .build();

        assertEquals(List.of("5000000000"), wireValues(config("AHC/test"), request, CONTENT_LENGTH));
    }
}
