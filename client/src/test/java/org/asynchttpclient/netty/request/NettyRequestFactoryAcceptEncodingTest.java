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

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.ACCEPT_ENCODING;
import static org.asynchttpclient.netty.request.NettyRequestFactory.filterOutUndecodableAcceptEncodings;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Brotli and zstd are both on the test classpath, so the availability flags are passed in rather than taken from
 * Netty's {@code Brotli.isAvailable()} and {@code Zstd.isAvailable()}.
 */
public class NettyRequestFactoryAcceptEncodingTest {

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

    private static List<String> filter(boolean brotliAvailable, boolean zstdAvailable, String... acceptEncodings) {
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.add(ACCEPT_ENCODING, Arrays.asList(acceptEncodings));
        filterOutUndecodableAcceptEncodings(headers, brotliAvailable, zstdAvailable);
        return headers.getAll(ACCEPT_ENCODING);
    }
}
