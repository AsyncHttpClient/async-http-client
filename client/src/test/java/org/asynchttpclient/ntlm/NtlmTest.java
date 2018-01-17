/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.ntlm;

import org.apache.commons.io.output.ByteArrayOutputStream;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Response;
import org.asynchttpclient.ntlm.NtlmEngine.Type2Message;
import org.asynchttpclient.util.Base64;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.Assert;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.asynchttpclient.Dsl.*;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

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

  private Realm.Builder realmBuilderBase() {
    return ntlmAuthRealm("Zaphod", "Beeblebrox")//
            .setNtlmDomain("Ursa-Minor")//
            .setNtlmHost("LightCity");
  }

  private void ntlmAuthTest(Realm.Builder realmBuilder) throws IOException, InterruptedException, ExecutionException {

    try (AsyncHttpClient client = asyncHttpClient(config().setRealm(realmBuilder))) {
      Future<Response> responseFuture = client.executeRequest(get(getTargetUrl()));
      int status = responseFuture.get().getStatusCode();
      Assert.assertEquals(status, 200);
    }
  }

  @Test
  public void lazyNTLMAuthTest() throws IOException, InterruptedException, ExecutionException {
    ntlmAuthTest(realmBuilderBase());
  }

  @Test
  public void preemptiveNTLMAuthTest() throws IOException, InterruptedException, ExecutionException {
    ntlmAuthTest(realmBuilderBase().setUsePreemptiveAuth(true));
  }

  @Test
  public void testGenerateType1Msg() {
    NtlmEngine engine = new NtlmEngine();
    String message = engine.generateType1Msg();
    assertEquals(message, "TlRMTVNTUAABAAAAAYIIogAAAAAoAAAAAAAAACgAAAAFASgKAAAADw==", "Incorrect type1 message generated");
  }

  @Test(expectedExceptions = NtlmEngineException.class)
  public void testGenerateType3MsgThrowsExceptionWhenChallengeTooShort() {
    NtlmEngine engine = new NtlmEngine();
    engine.generateType3Msg("username", "password", "localhost", "workstation", Base64.encode("a".getBytes()));
    fail("An NtlmEngineException must have occurred as challenge length is too short");
  }

  @Test(expectedExceptions = NtlmEngineException.class)
  public void testGenerateType3MsgThrowsExceptionWhenChallengeDoesNotFollowCorrectFormat() {
    NtlmEngine engine = new NtlmEngine();
    engine.generateType3Msg("username", "password", "localhost", "workstation", Base64.encode("challenge".getBytes()));
    fail("An NtlmEngineException must have occurred as challenge format is not correct");
  }

  @Test(expectedExceptions = NtlmEngineException.class)
  public void testGenerateType3MsgThworsExceptionWhenType2IndicatorNotPresent() throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    buf.write("NTLMSSP".getBytes(StandardCharsets.US_ASCII));
    buf.write(0);
    // type 2 indicator
    buf.write(3);
    buf.write(0);
    buf.write(0);
    buf.write(0);
    buf.write("challenge".getBytes());
    NtlmEngine engine = new NtlmEngine();
    engine.generateType3Msg("username", "password", "localhost", "workstation", Base64.encode(buf.toByteArray()));
    buf.close();
    fail("An NtlmEngineException must have occurred as type 2 indicator is incorrect");
  }

  @Test(expectedExceptions = NtlmEngineException.class)
  public void testGenerateType3MsgThrowsExceptionWhenUnicodeSupportNotIndicated() throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
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
    engine.generateType3Msg("username", "password", "localhost", "workstation", Base64.encode(buf.toByteArray()));
    buf.close();
    fail("An NtlmEngineException must have occurred as unicode support is not indicated");
  }

  @Test
  public void testGenerateType2Msg() {
    Type2Message type2Message = new Type2Message("TlRMTVNTUAACAAAAAAAAACgAAAABggAAU3J2Tm9uY2UAAAAAAAAAAA==");
    Assert.assertEquals(type2Message.getMessageLength(), 40, "This is a sample challenge that should return 40");
  }

  @Test
  public void testGenerateType3Msg() throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
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
    String type3Msg = engine.generateType3Msg("username", "password", "localhost", "workstation", Base64.encode(buf.toByteArray()));
    buf.close();
    assertEquals(
            type3Msg,
            "TlRMTVNTUAADAAAAGAAYAEgAAAAYABgAYAAAABIAEgB4AAAAEAAQAIoAAAAWABYAmgAAAAAAAACwAAAAAQAAAgUBKAoAAAAP1g6lqqN1HZ0wSSxeQ5riQkyh7/UexwVlCPQm0SHU2vsDQm2wM6NbT2zPonPzLJL0TABPAEMAQQBMAEgATwBTAFQAdQBzAGUAcgBuAGEAbQBlAFcATwBSAEsAUwBUAEEAVABJAE8ATgA=",
            "Incorrect type3 message generated");
  }

  @Test
  public void testWriteULong() {
    // test different combinations so that different positions in the byte array will be written
    byte[] buffer = new byte[4];
    NtlmEngine.writeULong(buffer, 1, 0);
    assertEquals(buffer, new byte[]{1, 0, 0, 0}, "Unsigned long value 1 was not written correctly to the buffer");

    buffer = new byte[4];
    NtlmEngine.writeULong(buffer, 257, 0);
    assertEquals(buffer, new byte[]{1, 1, 0, 0}, "Unsigned long value 257 was not written correctly to the buffer");

    buffer = new byte[4];
    NtlmEngine.writeULong(buffer, 16777216, 0);
    assertEquals(buffer, new byte[]{0, 0, 0, 1}, "Unsigned long value 16777216 was not written correctly to the buffer");
  }

  public static class NTLMHandler extends AbstractHandler {

    @Override
    public void handle(String pathInContext, org.eclipse.jetty.server.Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException,
            ServletException {

      String authorization = httpRequest.getHeader("Authorization");
      if (authorization == null) {
        httpResponse.setStatus(401);
        httpResponse.setHeader("WWW-Authenticate", "NTLM");

      } else if (authorization.equals("NTLM TlRMTVNTUAABAAAAAYIIogAAAAAoAAAAAAAAACgAAAAFASgKAAAADw==")) {
        httpResponse.setStatus(401);
        httpResponse.setHeader("WWW-Authenticate", "NTLM TlRMTVNTUAACAAAAAAAAACgAAAABggAAU3J2Tm9uY2UAAAAAAAAAAA==");

      } else if (authorization
              .equals("NTLM TlRMTVNTUAADAAAAGAAYAEgAAAAYABgAYAAAABQAFAB4AAAADAAMAIwAAAASABIAmAAAAAAAAACqAAAAAYIAAgUBKAoAAAAPrYfKbe/jRoW5xDxHeoxC1gBmfWiS5+iX4OAN4xBKG/IFPwfH3agtPEia6YnhsADTVQBSAFMAQQAtAE0ASQBOAE8AUgBaAGEAcABoAG8AZABMAEkARwBIAFQAQwBJAFQAWQA=")) {
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
