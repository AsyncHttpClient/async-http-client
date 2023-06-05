/*
 *    Copyright (c) 2014-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.ntlm;

import io.github.artsok.RepeatedIfExceptionsTest;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Response;
import org.asynchttpclient.ntlm.NtlmEngine.Type2Message;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.Dsl.ntlmAuthRealm;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class NtlmTest extends AbstractBasicTest {

    private static byte[] longToBytes(long x) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.putLong(x);
        return buffer.array();
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new NTLMHandler();
    }

    private static Realm.Builder realmBuilderBase() {
        return ntlmAuthRealm("Zaphod", "Beeblebrox")
                .setNtlmDomain("Ursa-Minor")
                .setNtlmHost("LightCity");
    }

    private void ntlmAuthTest(Realm.Builder realmBuilder) throws IOException, InterruptedException, ExecutionException {
        try (AsyncHttpClient client = asyncHttpClient(config().setRealm(realmBuilder))) {
            Future<Response> responseFuture = client.executeRequest(get(getTargetUrl()));
            int status = responseFuture.get().getStatusCode();
            assertEquals(200, status);
        }
    }

    @Test
    public void testUnicodeLittleUnmarkedEncoding() {
        final Charset unicodeLittleUnmarked = Charset.forName("UnicodeLittleUnmarked");
        final Charset utf16le = StandardCharsets.UTF_16LE;
        assertEquals(unicodeLittleUnmarked, utf16le);
        assertArrayEquals("Test @ テスト".getBytes(unicodeLittleUnmarked), "Test @ テスト".getBytes(utf16le));
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void lazyNTLMAuthTest() throws Exception {
        ntlmAuthTest(realmBuilderBase());
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void preemptiveNTLMAuthTest() throws Exception {
        ntlmAuthTest(realmBuilderBase().setUsePreemptiveAuth(true));
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testGenerateType1Msg() {
        NtlmEngine engine = new NtlmEngine();
        String message = engine.generateType1Msg();
        assertEquals(message, "TlRMTVNTUAABAAAAAYIIogAAAAAoAAAAAAAAACgAAAAFASgKAAAADw==", "Incorrect type1 message generated");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testGenerateType3MsgThrowsExceptionWhenChallengeTooShort() {
        NtlmEngine engine = new NtlmEngine();
        assertThrows(NtlmEngineException.class, () -> engine.generateType3Msg("username", "password", "localhost", "workstation",
                        Base64.getEncoder().encodeToString("a".getBytes())),
                "An NtlmEngineException must have occurred as challenge length is too short");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testGenerateType3MsgThrowsExceptionWhenChallengeDoesNotFollowCorrectFormat() {
        NtlmEngine engine = new NtlmEngine();
        assertThrows(NtlmEngineException.class, () -> engine.generateType3Msg("username", "password", "localhost", "workstation",
                        Base64.getEncoder().encodeToString("challenge".getBytes())),
                "An NtlmEngineException must have occurred as challenge length is too short");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testGenerateType3MsgThworsExceptionWhenType2IndicatorNotPresent() throws IOException {
        try (ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            buf.write("NTLMSSP".getBytes(StandardCharsets.US_ASCII));
            buf.write(0);
            // type 2 indicator
            buf.write(3);
            buf.write(0);
            buf.write(0);
            buf.write(0);
            buf.write("challenge".getBytes());
            NtlmEngine engine = new NtlmEngine();
            assertThrows(NtlmEngineException.class, () -> engine.generateType3Msg("username", "password", "localhost", "workstation",
                    Base64.getEncoder().encodeToString(buf.toByteArray())), "An NtlmEngineException must have occurred as type 2 indicator is incorrect");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testGenerateType3MsgThrowsExceptionWhenUnicodeSupportNotIndicated() throws IOException {
        try (ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            buf.write("NTLMSSP".getBytes(StandardCharsets.US_ASCII));
            buf.write(0);
            // type 2 indicator
            buf.write(2);
            buf.write(0);
            buf.write(0);
            buf.write(0);

            buf.write(longToBytes(1L)); // we want to write a Long

            // flags
            buf.write(0);// unicode support indicator
            buf.write(0);
            buf.write(0);
            buf.write(0);

            buf.write(longToBytes(1L));// challenge
            NtlmEngine engine = new NtlmEngine();
            assertThrows(NtlmEngineException.class, () -> engine.generateType3Msg("username", "password", "localhost", "workstation",
                            Base64.getEncoder().encodeToString(buf.toByteArray())),
                    "An NtlmEngineException must have occurred as unicode support is not indicated");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testGenerateType2Msg() {
        Type2Message type2Message = new Type2Message("TlRMTVNTUAACAAAAAAAAACgAAAABggAAU3J2Tm9uY2UAAAAAAAAAAA==");
        assertEquals(40, type2Message.getMessageLength(), "This is a sample challenge that should return 40");
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testGenerateType3Msg() throws IOException {
        try (ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            buf.write("NTLMSSP".getBytes(StandardCharsets.US_ASCII));
            buf.write(0);
            // type 2 indicator
            buf.write(2);
            buf.write(0);
            buf.write(0);
            buf.write(0);

            buf.write(longToBytes(0L)); // we want to write a Long

            // flags
            buf.write(1);// unicode support indicator
            buf.write(0);
            buf.write(0);
            buf.write(0);

            buf.write(longToBytes(1L));// challenge
            NtlmEngine engine = new NtlmEngine();
            String type3Msg = engine.generateType3Msg("username", "password", "localhost", "workstation",
                    Base64.getEncoder().encodeToString(buf.toByteArray()));
            assertEquals(type3Msg,
                    "TlRMTVNTUAADAAAAGAAYAEgAAAAYABgAYAAAABIAEgB4AAAAEAAQAIoAAAAWABYAmgAAAAAAAACwAAAAAQAAAgUBKAoAAAAP1g6lqqN1HZ0wSSxeQ5riQkyh7/UexwVlCPQm0SHU2vsDQm2wM6NbT2zPonPzLJL0TABPAEMAQQBMAEgATwBTAFQAdQBzAGUAcgBuAGEAbQBlAFcATwBSAEsAUwBUAEEAVABJAE8ATgA=",
                    "Incorrect type3 message generated");
        }
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    public void testWriteULong() {
        // test different combinations so that different positions in the byte array will be written
        byte[] buffer = new byte[4];
        NtlmEngine.writeULong(buffer, 1, 0);
        assertArrayEquals(new byte[]{1, 0, 0, 0}, buffer, "Unsigned long value 1 was not written correctly to the buffer");

        buffer = new byte[4];
        NtlmEngine.writeULong(buffer, 257, 0);
        assertArrayEquals(new byte[]{1, 1, 0, 0}, buffer, "Unsigned long value 257 was not written correctly to the buffer");

        buffer = new byte[4];
        NtlmEngine.writeULong(buffer, 16777216, 0);
        assertArrayEquals(new byte[]{0, 0, 0, 1}, buffer, "Unsigned long value 16777216 was not written correctly to the buffer");
    }

    public static class NTLMHandler extends AbstractHandler {

        @Override
        public void handle(String pathInContext, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException {
            String authorization = httpRequest.getHeader("Authorization");
            if (authorization == null) {
                httpResponse.setStatus(401);
                httpResponse.setHeader("WWW-Authenticate", "NTLM");

            } else if ("NTLM TlRMTVNTUAABAAAAAYIIogAAAAAoAAAAAAAAACgAAAAFASgKAAAADw==".equals(authorization)) {
                httpResponse.setStatus(401);
                httpResponse.setHeader("WWW-Authenticate", "NTLM TlRMTVNTUAACAAAAAAAAACgAAAABggAAU3J2Tm9uY2UAAAAAAAAAAA==");

            } else if ("NTLM TlRMTVNTUAADAAAAGAAYAEgAAAAYABgAYAAAABQAFAB4AAAADAAMAIwAAAASABIAmAAAAAAAAACqAAAAAYIAAgUBKAoAAAAPrYfKbe/jRoW5xDxHeoxC1gBmfWiS5+iX4OAN4xBKG/IFPwfH3agtPEia6YnhsADTVQBSAFMAQQAtAE0ASQBOAE8AUgBaAGEAcABoAG8AZABMAEkARwBIAFQAQwBJAFQAWQA="
                    .equals(authorization)) {
                httpResponse.setStatus(200);
            } else {
                httpResponse.setStatus(401);
            }

            httpResponse.setContentLength(0);
            httpResponse.getOutputStream().flush();
            httpResponse.getOutputStream().close();
        }
    }
}
