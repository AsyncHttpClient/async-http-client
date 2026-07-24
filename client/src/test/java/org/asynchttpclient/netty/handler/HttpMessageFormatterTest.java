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

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpMessageFormatterTest {

    @Test
    public void shouldRedactSensitiveRequestHeaders() {
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
        request.headers()
                .set("Authorization", "Bearer request-secret")
                .set("proxy-authorization", "Basic proxy-secret")
                .set("Cookie", "session=cookie-secret")
                .set("X-Request-Id", "request-id");

        String value = HttpMessageFormatter.format(request);

        assertFalse(value.contains("request-secret"));
        assertFalse(value.contains("proxy-secret"));
        assertFalse(value.contains("cookie-secret"));
        assertTrue(value.contains("Authorization: <redacted>"));
        assertTrue(value.contains("proxy-authorization: <redacted>"));
        assertTrue(value.contains("X-Request-Id: request-id"));
    }

    @Test
    public void shouldRedactSensitiveResponseHeaders() {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers()
                .set("Set-Cookie", "session=response-secret")
                .set("X-Request-Id", "request-id");

        String value = HttpMessageFormatter.format(response);

        assertFalse(value.contains("response-secret"));
        assertTrue(value.contains("Set-Cookie: <redacted>"));
        assertTrue(value.contains("X-Request-Id: request-id"));
    }

    @Test
    @EnabledIfSystemProperty(named = "org.asynchttpclient.enableSensitiveLogging", matches = "(?i)true")
    public void shouldIncludeSensitiveHeadersWhenSystemPropertyEnabled() {
        assertSensitiveHeadersIncluded();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AHC_ENABLE_SENSITIVE_LOGGING", matches = "(?i)true")
    public void shouldIncludeSensitiveHeadersWhenEnvironmentVariableEnabled() {
        assertSensitiveHeadersIncluded();
    }

    private static void assertSensitiveHeadersIncluded() {
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
        request.headers().set("Authorization", "Bearer request-secret");

        String value = HttpMessageFormatter.format(request);

        assertFalse(HttpMessageFormatter.isSensitiveHeader("Authorization"));
        assertTrue(value.contains("Authorization: Bearer request-secret"));
        assertFalse(value.contains(HttpMessageFormatter.REDACTED));
    }
}
