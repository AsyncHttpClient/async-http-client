package org.asynchttpclient;

import static org.asynchttpclient.Dsl.*;
import static org.testng.Assert.assertEquals;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.asynchttpclient.filter.FilterContext;
import org.asynchttpclient.filter.FilterException;
import org.asynchttpclient.filter.ResponseFilter;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

public class RedirectBodyTest extends AbstractBasicTest {

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new AbstractHandler() {
            @Override
            public void handle(String pathInContext, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException {

                String redirectHeader = httpRequest.getHeader("X-REDIRECT");
                if (redirectHeader != null) {
                    httpResponse.setStatus(Integer.valueOf(redirectHeader));
                    httpResponse.setContentLength(0);
                    httpResponse.setHeader("Location", getTargetUrl());

                } else {
                    httpResponse.setStatus(200);
                    int len = request.getContentLength();
                    httpResponse.setContentLength(len);
                    if (len > 0) {
                        byte[] buffer = new byte[len];
                        IOUtils.read(request.getInputStream(), buffer);
                        httpResponse.getOutputStream().write(buffer);
                    }
                }
                httpResponse.getOutputStream().flush();
                httpResponse.getOutputStream().close();
            }
        };
    }

    private ResponseFilter redirectOnce = new ResponseFilter() {
        @Override
        public <T> FilterContext<T> filter(FilterContext<T> ctx) throws FilterException {
            ctx.getRequest().getHeaders().remove("X-REDIRECT");
            return ctx;
        }
    };

    @Test(groups = "standalone")
    public void regular301LosesBody() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true).addResponseFilter(redirectOnce))) {
            String body = "hello there";

            Response response = c.preparePost(getTargetUrl()).setBody(body).setHeader("X-REDIRECT", "301").execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(response.getResponseBody(), "");
        }
    }

    @Test(groups = "standalone")
    public void regular302LosesBody() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true).addResponseFilter(redirectOnce))) {
            String body = "hello there";

            Response response = c.preparePost(getTargetUrl()).setBody(body).setHeader("X-REDIRECT", "302").execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(response.getResponseBody(), "");
        }
    }

    @Test(groups = "standalone")
    public void regular302StrictKeepsBody() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true).setStrict302Handling(true).addResponseFilter(redirectOnce))) {
            String body = "hello there";

            Response response = c.preparePost(getTargetUrl()).setBody(body).setHeader("X-REDIRECT", "302").execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(response.getResponseBody(), body);
        }
    }

    @Test(groups = "standalone")
    public void regular307KeepsBody() throws Exception {
        try (AsyncHttpClient c = asyncHttpClient(config().setFollowRedirect(true).addResponseFilter(redirectOnce))) {
            String body = "hello there";

            Response response = c.preparePost(getTargetUrl()).setBody(body).setHeader("X-REDIRECT", "307").execute().get(TIMEOUT, TimeUnit.SECONDS);
            assertEquals(response.getResponseBody(), body);
        }
    }
}
