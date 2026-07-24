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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Http2HandlerTest {

    @Test
    void copiesDecoderValidatedHeaderNamesWithoutRevalidation() {
        Http2Headers source = new DefaultHttp2Headers(false)
                .status("200")
                .add("invalid name", "value");

        HttpHeaders copy = Http2Handler.copyHttp2Headers(source);

        assertEquals("value", copy.get("invalid name"));
        assertFalse(copy.contains(":status"));
    }

    @Test
    void stillValidatesHeaderValues() {
        Http2Headers source = new DefaultHttp2Headers(false)
                .add("x-test", "value\r\ninjected");

        assertThrows(IllegalArgumentException.class, () -> Http2Handler.copyHttp2Headers(source));
    }
}
