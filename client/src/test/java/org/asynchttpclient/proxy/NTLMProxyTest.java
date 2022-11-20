/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.proxy;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Response;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.get;
import static org.asynchttpclient.Dsl.ntlmAuthRealm;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class NTLMProxyTest extends AbstractBasicTest {

    public static AbstractHandler configureHandler() throws Exception {
        return new NTLMProxyHandler();
    }

    @Test
    public void ntlmProxyTest() throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            org.asynchttpclient.Request request = get("http://localhost").setProxyServer(ntlmProxy()).build();
            Future<Response> responseFuture = client.executeRequest(request);
            int status = responseFuture.get().getStatusCode();
            assertEquals(200, status);
        }
    }

    private static ProxyServer ntlmProxy() {
        Realm realm = ntlmAuthRealm("Zaphod", "Beeblebrox")
                .setNtlmDomain("Ursa-Minor")
                .setNtlmHost("LightCity")
                .build();
        return proxyServer("localhost", port2).setRealm(realm).build();
    }

    public static class NTLMProxyHandler extends AbstractHandler {

        private final AtomicInteger state = new AtomicInteger();

        @Override
        public void handle(String pathInContext, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException {

            String authorization = httpRequest.getHeader("Proxy-Authorization");
            boolean asExpected = false;

            switch (state.getAndIncrement()) {
                case 0:
                    if (authorization == null) {
                        httpResponse.setStatus(HttpStatus.PROXY_AUTHENTICATION_REQUIRED_407);
                        httpResponse.setHeader("Proxy-Authenticate", "NTLM");
                        asExpected = true;
                    }
                    break;
                case 1:
                    if ("NTLM TlRMTVNTUAABAAAAAYIIogAAAAAoAAAAAAAAACgAAAAFASgKAAAADw==".equals(authorization)) {
                        httpResponse.setStatus(HttpStatus.PROXY_AUTHENTICATION_REQUIRED_407);
                        httpResponse.setHeader("Proxy-Authenticate", "NTLM TlRMTVNTUAACAAAAAAAAACgAAAABggAAU3J2Tm9uY2UAAAAAAAAAAA==");
                        asExpected = true;
                    }
                    break;
                case 2:
                    if ("NTLM TlRMTVNTUAADAAAAGAAYAEgAAAAYABgAYAAAABQAFAB4AAAADAAMAIwAAAASABIAmAAAAAAAAACqAAAAAYIAAgUBKAoAAAAPrYfKbe/jRoW5xDxHeoxC1gBmfWiS5+iX4OAN4xBKG/IFPwfH3agtPEia6YnhsADTVQBSAFMAQQAtAE0ASQBOAE8AUgBaAGEAcABoAG8AZABMAEkARwBIAFQAQwBJAFQAWQA="
                            .equals(authorization)) {
                        httpResponse.setStatus(HttpStatus.OK_200);
                        asExpected = true;
                    }
                    break;
                default:
            }

            if (!asExpected) {
                httpResponse.setStatus(HttpStatus.FORBIDDEN_403);
            }
            httpResponse.setContentLength(0);
            httpResponse.getOutputStream().flush();
            httpResponse.getOutputStream().close();
        }
    }
}
