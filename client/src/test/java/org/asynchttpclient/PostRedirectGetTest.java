/*
 *    Copyright (c) 2012-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient;

import io.github.artsok.RepeatedIfExceptionsTest;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.ResponseFilter;
import org.eclipse.jetty.server.handler.AbstractHandler;

import java.io.IOException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.Dsl.post;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class PostRedirectGetTest extends AbstractBasicTest {

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new PostRedirectGetHandler();
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void postRedirectGet302Test() throws Exception {
        doTestPositive(302);
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void postRedirectGet302StrictTest() throws Exception {
        doTestNegative(302, true);
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void postRedirectGet303Test() throws Exception {
        doTestPositive(303);
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void postRedirectGet301Test() throws Exception {
        doTestPositive(301);
    }

    @RepeatedIfExceptionsTest(repeats = 10)
    public void postRedirectGet307Test() throws Exception {
        doTestNegative(307, false);
    }

    // --------------------------------------------------------- Private Methods

    private void doTestNegative(final int status, boolean strict) throws Exception {

        ResponseFilter responseFilter = new ResponseFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                // pass on the x-expect-get and remove the x-redirect
                // headers if found in the response
                ctx.getResponseHeaders().get("x-expect-post");
                ctx.getRequest().getHeaders().add("x-expect-post", "true");
                ctx.getRequest().getHeaders().remove("x-redirect");
                return ctx;
            }
        };

        try (AsyncHttpClient p = asyncHttpClient(config().setFollowRedirect(true).setStrict302Handling(strict).addResponseFilter(responseFilter))) {
            Request request = post(getTargetUrl()).addFormParam("q", "a b").addHeader("x-redirect", +status + "@" + "http://localhost:" + port1 + "/foo/bar/baz").addHeader("x-negative", "true").build();
            Future<Integer> responseFuture = p.executeRequest(request, new AsyncCompletionHandler<Integer>() {

                @Override
                public Integer onCompleted(Response response) {
                    return response.getStatusCode();
                }

                @Override
                public void onThrowable(Throwable t) {
                    t.printStackTrace();
                    fail("Unexpected exception: " + t.getMessage(), t);
                }

            });
            int statusCode = responseFuture.get();
            assertEquals(statusCode, 200);
        }
    }

    private void doTestPositive(final int status) throws Exception {

        ResponseFilter responseFilter = new ResponseFilter() {
            @Override
            public <T> FilterContext<T> filter(FilterContext<T> ctx) {
                // pass on the x-expect-get and remove the x-redirect
                // headers if found in the response
                ctx.getResponseHeaders().get("x-expect-get");
                ctx.getRequest().getHeaders().add("x-expect-get", "true");
                ctx.getRequest().getHeaders().remove("x-redirect");
                return ctx;
            }
        };

        try (AsyncHttpClient p = asyncHttpClient(config().setFollowRedirect(true).addResponseFilter(responseFilter))) {
            Request request = post(getTargetUrl()).addFormParam("q", "a b").addHeader("x-redirect", +status + "@" + "http://localhost:" + port1 + "/foo/bar/baz").build();
            Future<Integer> responseFuture = p.executeRequest(request, new AsyncCompletionHandler<Integer>() {

                @Override
                public Integer onCompleted(Response response) {
                    return response.getStatusCode();
                }

                @Override
                public void onThrowable(Throwable t) {
                    t.printStackTrace();
                    fail("Unexpected exception: " + t.getMessage(), t);
                }

            });
            int statusCode = responseFuture.get();
            assertEquals(statusCode, 200);
        }
    }

    // ---------------------------------------------------------- Nested Classes

    public static class PostRedirectGetHandler extends AbstractHandler {

        final AtomicInteger counter = new AtomicInteger();

        @Override
        public void handle(String pathInContext, org.eclipse.jetty.server.Request request, HttpServletRequest httpRequest,
                           HttpServletResponse httpResponse) throws IOException, ServletException {

            final boolean expectGet = httpRequest.getHeader("x-expect-get") != null;
            final boolean expectPost = httpRequest.getHeader("x-expect-post") != null;
            if (expectGet) {
                final String method = request.getMethod();
                if (!"GET".equals(method)) {
                    httpResponse.sendError(500, "Incorrect method.  Expected GET, received " + method);
                    return;
                }
                httpResponse.setStatus(200);
                httpResponse.getOutputStream().write("OK".getBytes());
                httpResponse.getOutputStream().flush();
                return;
            }
            if (expectPost) {
                final String method = request.getMethod();
                if (!"POST".equals(method)) {
                    httpResponse.sendError(500, "Incorrect method.  Expected POST, received " + method);
                    return;
                }
                httpResponse.setStatus(200);
                httpResponse.getOutputStream().write("OK".getBytes());
                httpResponse.getOutputStream().flush();
                return;
            }

            String header = httpRequest.getHeader("x-redirect");
            if (header != null) {
                // format for header is <status code>|<location url>
                String[] parts = header.split("@");
                int redirectCode;
                try {
                    redirectCode = Integer.parseInt(parts[0]);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    httpResponse.sendError(500, "Unable to parse redirect code");
                    return;
                }
                httpResponse.setStatus(redirectCode);
                if (httpRequest.getHeader("x-negative") == null) {
                    httpResponse.addHeader("x-expect-get", "true");
                } else {
                    httpResponse.addHeader("x-expect-post", "true");
                }
                httpResponse.setContentLength(0);
                httpResponse.addHeader("Location", parts[1] + counter.getAndIncrement());
                httpResponse.getOutputStream().flush();
                return;
            }

            httpResponse.sendError(500);
            httpResponse.getOutputStream().flush();
            httpResponse.getOutputStream().close();
        }
    }
}
