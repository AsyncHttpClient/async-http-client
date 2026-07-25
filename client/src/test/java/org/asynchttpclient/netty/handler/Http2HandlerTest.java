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
package org.asynchttpclient.netty.handler;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import org.junit.jupiter.api.Test;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.TRAILER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Http2HandlerTest {

    @Test
    void copiesRegularHeadersAndSkipsPseudoHeaders() {
        Http2Headers source = new DefaultHttp2Headers(false)
                .status("200")
                .add(CONTENT_LENGTH, "5")
                .add("x-test", "value");

        HttpHeaders copy = Http2Handler.copyHttp2Headers(source);

        assertEquals("5", copy.get(CONTENT_LENGTH));
        assertEquals("value", copy.get("x-test"));
        assertFalse(copy.contains(":status"));
    }

    @Test
    void rejectsInvalidHeaderNames() {
        Http2Headers crlf = new DefaultHttp2Headers(false)
                .add("invalid\r\nname", "value");
        Http2Headers space = new DefaultHttp2Headers(false)
                .add("invalid name", "value");
        Http2Headers parenthesis = new DefaultHttp2Headers(false)
                .add("invalid(name", "value");

        assertThrows(IllegalArgumentException.class, () -> Http2Handler.copyHttp2Headers(crlf));
        assertThrows(IllegalArgumentException.class, () -> Http2Handler.copyHttp2Trailers(crlf));
        assertThrows(IllegalArgumentException.class, () -> Http2Handler.copyHttp2Headers(space));
        assertThrows(IllegalArgumentException.class, () -> Http2Handler.copyHttp2Trailers(space));
        assertThrows(IllegalArgumentException.class, () -> Http2Handler.copyHttp2Headers(parenthesis));
        assertThrows(IllegalArgumentException.class, () -> Http2Handler.copyHttp2Trailers(parenthesis));
    }

    @Test
    void skipsEmptyHeaderNames() {
        Http2Headers source = new DefaultHttp2Headers(false)
                .add("", "value");

        assertTrue(Http2Handler.copyHttp2Headers(source).isEmpty());
    }

    @Test
    void rejectsInvalidHeaderValues() {
        Http2Headers source = new DefaultHttp2Headers(false)
                .add("x-test", "value\r\ninjected");

        assertThrows(IllegalArgumentException.class, () -> Http2Handler.copyHttp2Headers(source));
        assertThrows(IllegalArgumentException.class, () -> Http2Handler.copyHttp2Trailers(source));
    }

    @Test
    void dropsProhibitedTrailingHeaders() {
        Http2Headers source = new DefaultHttp2Headers(false)
                .status("200")
                .add("grpc-status", "0")
                .add(CONTENT_LENGTH, "5")
                .add(TRAILER, "x-checksum");

        HttpHeaders copy = Http2Handler.copyHttp2Trailers(source);

        assertEquals("0", copy.get("grpc-status"));
        assertFalse(copy.contains(CONTENT_LENGTH));
        assertFalse(copy.contains(TRAILER));
        assertFalse(copy.contains(":status"));
    }
}
