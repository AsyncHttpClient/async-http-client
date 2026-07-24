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
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersEncoder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersEncoder;
import io.netty.util.AsciiString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AcceptEncodingHpackTest {

    private static final AsciiString ACCEPT_ENCODING = AsciiString.cached("accept-encoding");

    @Test
    public void staticTableSpellingUsesOneByteIndexedRepresentation() throws Exception {
        assertTrue(encodedLength("gzip,deflate") > 1);
        assertEquals(1, encodedLength("gzip, deflate"));
    }

    private static int encodedLength(String value) throws Exception {
        Http2HeadersEncoder encoder = new DefaultHttp2HeadersEncoder();
        Http2Headers headers = new DefaultHttp2Headers().add(ACCEPT_ENCODING, value);
        ByteBuf output = Unpooled.buffer();
        try {
            encoder.encodeHeaders(3, headers, output);
            return output.readableBytes();
        } finally {
            output.release();
        }
    }
}
