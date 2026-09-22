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
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The request a redirect leads to must carry what the redirect response just stored, not the cookies the
 * previous request went out with, whether the redirect keeps the method or switches to GET.
 */
public class RedirectCookieRotationTest extends AbstractBasicTest {

    private static final String RECEIVED_COOKIE = "received-cookie";

    @Override
    public AbstractHandler configureHandler() {
        return new AbstractHandler() {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
                switch (target) {
                    case "/seed":
                        response.addHeader("Set-Cookie", "SID=old; Path=/");
                        response.addHeader("Set-Cookie", "P=scoped; Path=/p");
                        break;
                    case "/login":
                        redirect(response, HttpServletResponse.SC_FOUND, "SID=new; Path=/", "/home");
                        break;
                    case "/login-307":
                        redirect(response, 307, "SID=new; Path=/", "/home");
                        break;
                    case "/logout":
                        redirect(response, HttpServletResponse.SC_FOUND, "SID=; Path=/; Max-Age=0", "/home");
                        break;
                    case "/logout-303":
                        redirect(response, HttpServletResponse.SC_SEE_OTHER, "SID=; Path=/; Max-Age=0", "/home");
                        break;
                    case "/p/a":
                        redirect(response, HttpServletResponse.SC_FOUND, null, "/q/b");
                        break;
                    default:
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
    void aSessionRotatedByARedirectIsTheOneSent() throws Exception {
        assertEquals("SID=new", afterSeeding(client -> client.prepareGet(url("/login"))), "GET, 302");
        assertEquals("SID=new", afterSeeding(client -> client.preparePost(url("/login"))), "POST, 302 to GET");
        assertEquals("SID=new", afterSeeding(client -> client.preparePost(url("/login-307"))), "POST, 307");
    }

    @Test
    void aCookieARedirectDeletedStaysDeleted() throws Exception {
        assertNull(afterSeeding(client -> client.prepareGet(url("/logout"))), "GET, 302");
        assertNull(afterSeeding(client -> client.preparePost(url("/logout-303"))), "POST, 303");
    }

    @Test
    void aPathScopedCookieDoesNotFollowARedirectOutOfItsPath() throws Exception {
        String received = afterSeeding(client -> client.prepareGet(url("/p/a")));
        assertFalse(received != null && received.contains("P="), "sent to /q/b: " + received);
    }

    private String afterSeeding(Function<AsyncHttpClient, BoundRequestBuilder> request) throws Exception {
        try (AsyncHttpClient client = asyncHttpClient(config().setFollowRedirect(true))) {
            client.prepareGet(url("/seed")).execute().get(TIMEOUT, TimeUnit.SECONDS);
            return request.apply(client).execute().get(TIMEOUT, TimeUnit.SECONDS).getHeader(RECEIVED_COOKIE);
        }
    }

    private static void redirect(HttpServletResponse response, int status, String setCookie, String location) {
        if (setCookie != null) {
            response.addHeader("Set-Cookie", setCookie);
        }
        response.setStatus(status);
        response.setHeader("Location", location);
    }

    private String url(String path) {
        return "http://localhost:" + port1 + path;
    }
}
