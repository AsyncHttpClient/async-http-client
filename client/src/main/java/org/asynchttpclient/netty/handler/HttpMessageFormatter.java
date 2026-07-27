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
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import org.asynchttpclient.util.SensitiveLoggingUtils;

import java.util.Map;

/**
 * Formats HTTP messages for logging. Sensitive header values are redacted unless the
 * {@code org.asynchttpclient.enableSensitiveLogging} system property or {@code AHC_ENABLE_SENSITIVE_LOGGING} environment
 * variable is set to {@code true} before the sensitive logging policy is initialized. Enabling sensitive logging may
 * expose credentials and session data.
 */
public final class HttpMessageFormatter {

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
                    .append(SensitiveLoggingUtils.isSensitiveHeader(header.getKey()) ? SensitiveLoggingUtils.REDACTED : header.getValue());
        }
        return value;
    }
}
