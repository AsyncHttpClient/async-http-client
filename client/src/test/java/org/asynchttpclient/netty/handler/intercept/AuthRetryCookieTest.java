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

import io.netty.handler.codec.http.cookie.DefaultCookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.BoundRequestBuilder;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.basicAuthRealm;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The retry after a 401 must carry the cookies the challenge set, not the ones the first attempt went out with.
 */
public class AuthRetryCookieTest extends AbstractBasicTest {

    private static final String RECEIVED_COOKIE = "received-cookie";

    @Override
    public AbstractHandler configureHandler() {
        return new AbstractHandler() {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
                if ("/seed".equals(target)) {
                    response.addHeader("Set-Cookie", "SID=old; Path=/");
                } else if (request.getHeader("Authorization") == null) {
                    response.addHeader("Set-Cookie", "SID=new; Path=/");
                    response.setHeader("WWW-Authenticate", "Basic realm=\"test\"");
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                } else {
                    String cookie = request.getHeader("Cookie");
                    if (cookie != null) {
                        response.setHeader(RECEIVED_COOKIE, cookie);
                    }
                }
                baseRequest.setHandled(true);
            }
        };
    }

    @Test
    void theRetrySendsTheSessionTheChallengeSet() throws Exception {
        assertEquals(new HashSet<>(Arrays.asList("SID=new")), cookiesOnRetry(null));
    }

    @Test
    void theCallersCookieSurvivesTheRetry() throws Exception {
        assertEquals(new HashSet<>(Arrays.asList("X=1", "SID=new")), cookiesOnRetry(new DefaultCookie("X", "1")));
    }

    private Set<String> cookiesOnRetry(DefaultCookie callerCookie) throws Exception {
        try (AsyncHttpClient client = asyncHttpClient()) {
            client.prepareGet(url("/seed")).execute().get(TIMEOUT, TimeUnit.SECONDS);
            BoundRequestBuilder request = client.prepareGet(url("/protected"))
                    .setRealm(basicAuthRealm("user", "pass").setUsePreemptiveAuth(false));
            if (callerCookie != null) {
                request.addCookie(callerCookie);
            }
            String header = request.execute().get(TIMEOUT, TimeUnit.SECONDS).getHeader(RECEIVED_COOKIE);
            return header == null ? new HashSet<>() : new HashSet<>(Arrays.asList(header.split("; ")));
        }
    }

    private String url(String path) {
        return "http://localhost:" + port1 + path;
    }
}
