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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpMessageFormatterTest {

    @TempDir
    private Path tempDirectory;

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
    public void shouldIncludeSensitiveHeadersWhenSystemPropertyEnabled() throws Exception {
        String output = runProbe("true", null);

        assertTrue(output.contains("Authorization: Bearer request-secret"), output);
        assertTrue(output.contains("Sensitive HTTP header logging is enabled"), output);
    }

    @Test
    public void shouldIncludeSensitiveHeadersWhenEnvironmentVariableEnabled() throws Exception {
        String output = runProbe(null, "true");

        assertTrue(output.contains("Authorization: Bearer request-secret"), output);
        assertTrue(output.contains("Sensitive HTTP header logging is enabled"), output);
    }

    @Test
    public void systemPropertyShouldOverrideEnvironmentVariable() throws Exception {
        String output = runProbe("false", "true");

        assertTrue(output.contains("Authorization: <redacted>"), output);
        assertFalse(output.contains("Sensitive HTTP header logging is enabled"), output);
    }

    private String runProbe(String propertyValue, String environmentValue) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        if (propertyValue != null) {
            command.add("-Dorg.asynchttpclient.enableSensitiveLogging=" + propertyValue);
        }
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(SensitiveLoggingProbe.class.getName());

        Path outputFile = Files.createTempFile(tempDirectory, "sensitive-logging-probe-", ".log");
        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile());
        if (environmentValue == null) {
            processBuilder.environment().remove("AHC_ENABLE_SENSITIVE_LOGGING");
        } else {
            processBuilder.environment().put("AHC_ENABLE_SENSITIVE_LOGGING", environmentValue);
        }

        Process process = processBuilder.start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            throw new AssertionError("Sensitive logging probe timed out\n" + Files.readString(outputFile, UTF_8));
        }
        String output = Files.readString(outputFile, UTF_8);
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    private static Path javaExecutable() throws IOException {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        Path path = Paths.get(System.getProperty("java.home"), "bin", executable);
        return path.toRealPath();
    }
}
