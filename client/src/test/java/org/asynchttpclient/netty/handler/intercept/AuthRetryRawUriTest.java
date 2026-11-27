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
package org.asynchttpclient.netty.handler.intercept;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.Realm;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.AUTHORIZATION;
import static io.netty.handler.codec.http.HttpHeaderNames.PROXY_AUTHENTICATE;
import static io.netty.handler.codec.http.HttpHeaderNames.PROXY_AUTHORIZATION;
import static io.netty.handler.codec.http.HttpHeaderNames.WWW_AUTHENTICATE;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.basicAuthRealm;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.digestAuthRealm;
import static org.asynchttpclient.Dsl.proxyServer;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The retry that answers a 401 or a 407 is a copy of the request that was challenged, and must keep its request
 * target as it was built: for a request built with URL encoding disabled, that is not the encoded form.
 */
public class AuthRetryRawUriTest extends AbstractBasicTest {

    private static final String TARGET = "/a|b?q={x}";
    private static final String ENCODED_TARGET = "/a%7Cb?q=%7Bx%7D";
    private static final String BASIC = "Basic realm=\"r\"";
    private static final String DIGEST = "Digest realm=\"r\", nonce=\"n\", qop=\"auth\"";

    private final List<String> targets = new CopyOnWriteArrayList<>();
    private volatile Challenge challenge;

    // Answers a request without credentials with the challenge, and records each request target as it arrived.
    @Override
    public AbstractHandler configureHandler() {
        return new AbstractHandler() {
            @Override
            public void handle(String s, Request r, HttpServletRequest request, HttpServletResponse response) {
                targets.add(request.getRequestURI() + '?' + request.getQueryString());
                Challenge current = challenge;
                if (request.getHeader(current.credentialsHeader) == null) {
                    response.setStatus(current.status);
                    response.setHeader(current.challengeHeader, current.challenge);
                }
                r.setHandled(true);
            }
        };
    }

    @BeforeEach
    public void clearTargets() {
        targets.clear();
    }

    @Test
    public void aBasicRetryKeepsAUriBuiltWithoutEncoding() throws Exception {
        challengeWith(401, WWW_AUTHENTICATE, BASIC, AUTHORIZATION);

        sendThroughTheChallenge(true, basicAuthRealm("user", "secret"), false);

        assertEquals(List.of(TARGET, TARGET), targets);
    }

    @Test
    public void aDigestRetryKeepsAUriBuiltWithoutEncoding() throws Exception {
        challengeWith(401, WWW_AUTHENTICATE, DIGEST, AUTHORIZATION);

        sendThroughTheChallenge(true, digestAuthRealm("user", "secret"), false);

        assertEquals(List.of(TARGET, TARGET), targets);
    }

    @Test
    public void aBasicProxyRetryKeepsAUriBuiltWithoutEncoding() throws Exception {
        challengeWith(407, PROXY_AUTHENTICATE, BASIC, PROXY_AUTHORIZATION);

        sendThroughTheChallenge(true, basicAuthRealm("user", "secret"), true);

        assertEquals(List.of(TARGET, TARGET), targets);
    }

    @Test
    public void aDigestProxyRetryKeepsAUriBuiltWithoutEncoding() throws Exception {
        challengeWith(407, PROXY_AUTHENTICATE, DIGEST, PROXY_AUTHORIZATION);

        sendThroughTheChallenge(true, digestAuthRealm("user", "secret"), true);

        assertEquals(List.of(TARGET, TARGET), targets);
    }

    @Test
    public void aRetryKeepsAnEncodedUri() throws Exception {
        challengeWith(401, WWW_AUTHENTICATE, BASIC, AUTHORIZATION);

        sendThroughTheChallenge(false, basicAuthRealm("user", "secret"), false);

        assertEquals(List.of(ENCODED_TARGET, ENCODED_TARGET), targets);
    }

    private void challengeWith(int status, CharSequence challengeHeader, String challenge,
                               CharSequence credentialsHeader) {
        this.challenge = new Challenge(status, challengeHeader.toString(), challenge, credentialsHeader.toString());
    }

    // As a proxy, the server receives the request for an origin it never connects to.
    private void sendThroughTheChallenge(boolean disableUrlEncoding, Realm.Builder realm, boolean toTheProxy)
            throws Exception {
        Realm notPreemptive = realm.setUsePreemptiveAuth(false).build();
        try (AsyncHttpClient client = asyncHttpClient(config()
                .setDisableUrlEncodingForBoundRequests(disableUrlEncoding))) {
            BoundRequestBuilder request = toTheProxy
                    ? client.prepareGet("http://origin.example" + TARGET)
                            .setProxyServer(proxyServer("localhost", port1).setRealm(notPreemptive))
                    : client.prepareGet("http://localhost:" + port1 + TARGET).setRealm(notPreemptive);

            assertEquals(200, request.execute().get(TIMEOUT, TimeUnit.SECONDS).getStatusCode());
        }
    }

    private static final class Challenge {
        final int status;
        final String challengeHeader;
        final String challenge;
        final String credentialsHeader;

        Challenge(int status, String challengeHeader, String challenge, String credentialsHeader) {
            this.status = status;
            this.challengeHeader = challengeHeader;
            this.challenge = challenge;
            this.credentialsHeader = credentialsHeader;
        }
    }
}
