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

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.util.Map;

/**
 * Formats HTTP messages for logging. Sensitive header values are redacted unless the
 * {@code org.asynchttpclient.enableSensitiveLogging} system property or {@code AHC_ENABLE_SENSITIVE_LOGGING} environment
 * variable is set to {@code true} when this class is initialized. Enabling sensitive logging may expose credentials and
 * session data.
 */
public final class HttpMessageFormatter {

    private static final String ENABLE_SENSITIVE_LOGGING_PROPERTY = "org.asynchttpclient.enableSensitiveLogging";
    private static final String ENABLE_SENSITIVE_LOGGING_ENVIRONMENT_VARIABLE = "AHC_ENABLE_SENSITIVE_LOGGING";
    private static final boolean SENSITIVE_LOGGING_ENABLED = isSensitiveLoggingEnabled();

    /** The value used in place of sensitive header values. */
    public static final String REDACTED = "<redacted>";

    private HttpMessageFormatter() {
    }

    static String format(HttpRequest request) {
        StringBuilder value = new StringBuilder()
                .append(request.method()).append(' ')
                .append(request.uri()).append(' ')
                .append(request.protocolVersion());
        return appendHeaders(value, request.headers()).toString();
    }

    static String format(HttpResponse response) {
        StringBuilder value = new StringBuilder()
                .append(response.protocolVersion()).append(' ')
                .append(response.status());
        return appendHeaders(value, response.headers()).toString();
    }

    private static StringBuilder appendHeaders(StringBuilder value, HttpHeaders headers) {
        for (Map.Entry<String, String> header : headers) {
            value.append('\n').append(header.getKey()).append(": ")
                    .append(isSensitiveHeader(header.getKey()) ? REDACTED : header.getValue());
        }
        return value;
    }

    private static boolean isSensitiveLoggingEnabled() {
        String propertyValue = System.getProperty(ENABLE_SENSITIVE_LOGGING_PROPERTY);
        String value = propertyValue != null ? propertyValue : System.getenv(ENABLE_SENSITIVE_LOGGING_ENVIRONMENT_VARIABLE);
        return Boolean.parseBoolean(value);
    }

    /**
     * Returns whether a header value must be redacted from logs.
     *
     * @param name the header name
     * @return {@code true} for authentication and cookie headers when sensitive logging is disabled
     */
    public static boolean isSensitiveHeader(CharSequence name) {
        return !SENSITIVE_LOGGING_ENABLED && (HttpHeaderNames.AUTHORIZATION.contentEqualsIgnoreCase(name)
                || HttpHeaderNames.PROXY_AUTHORIZATION.contentEqualsIgnoreCase(name)
                || HttpHeaderNames.COOKIE.contentEqualsIgnoreCase(name)
                || HttpHeaderNames.SET_COOKIE.contentEqualsIgnoreCase(name));
    }
}
