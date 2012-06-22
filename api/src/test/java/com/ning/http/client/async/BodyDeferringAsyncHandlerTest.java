/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.ning.http.client.async;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.BodyDeferringAsyncHandler;
import com.ning.http.client.BodyDeferringAsyncHandler.BodyDeferringInputStream;
import com.ning.http.client.Response;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.Assert;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public abstract class BodyDeferringAsyncHandlerTest extends AbstractBasicTest {

    // not a half gig ;) for test shorter run's sake
    protected static final int HALF_GIG = 100000;

    public static class SlowAndBigHandler extends AbstractHandler {

        public void handle(String pathInContext, Request request,
                HttpServletRequest httpRequest, HttpServletResponse httpResponse)
                throws IOException, ServletException {

            // 512MB large download
            // 512 * 1024 * 1024 = 536870912
            httpResponse.setStatus(200);
            httpResponse.setContentLength(HALF_GIG);
            httpResponse.setContentType("application/octet-stream");

            httpResponse.flushBuffer();

            final boolean wantFailure = httpRequest
                    .getHeader("X-FAIL-TRANSFER") != null;
            final boolean wantSlow = httpRequest.getHeader("X-SLOW") != null;

            OutputStream os = httpResponse.getOutputStream();
            for (int i = 0; i < HALF_GIG; i++) {
                os.write(i % 255);

                if (wantSlow) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ex) {
                        // nuku
                    }
                }

                if (wantFailure) {
                    if (i > HALF_GIG / 2) {
                        // kaboom
                        // yes, response is commited, but Jetty does aborts and
                        // drops connection
                        httpResponse.sendError(500);
                        break;
                    }
                }
            }

            httpResponse.getOutputStream().flush();
            httpResponse.getOutputStream().close();
        }
    }

    // a /dev/null but counting how many bytes it ditched
    public static class CountingOutputStream extends OutputStream {
        private int byteCount = 0;

        @Override
        public void write(int b) throws IOException {
            // /dev/null
            byteCount++;
        }

        public int getByteCount() {
            return byteCount;
        }
    }

    // simple stream copy just to "consume". It closes streams.
    public static void copy(InputStream in, OutputStream out)
            throws IOException {
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
        out.flush();
        out.close();
        in.close();
    }

    public AbstractHandler configureHandler() throws Exception {
        return new SlowAndBigHandler();
    }

    public AsyncHttpClientConfig getAsyncHttpClientConfig() {
        // for this test brevity's sake, we are limiting to 1 retries
        return new AsyncHttpClientConfig.Builder().setMaxRequestRetry(0)
                .setRequestTimeoutInMs(10000).build();
    }

    @Test(groups = { "standalone", "default_provider" })
    public void deferredSimple() throws IOException, ExecutionException,
            TimeoutException, InterruptedException {
        AsyncHttpClient client = getAsyncHttpClient(getAsyncHttpClientConfig());
        AsyncHttpClient.BoundRequestBuilder r = client
                .prepareGet("http://127.0.0.1:" + port1 + "/deferredSimple");

        CountingOutputStream cos = new CountingOutputStream();
        BodyDeferringAsyncHandler bdah = new BodyDeferringAsyncHandler(cos);
        Future<Response> f = r.execute(bdah);
        Response resp = bdah.getResponse();
        assertNotNull(resp);
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        assertEquals(
                true,
                resp.getHeader("content-length").equals(
                        String.valueOf(HALF_GIG)));
        // we got headers only, it's probably not all yet here (we have BIG file
        // downloading)
        assertEquals(true, HALF_GIG >= cos.getByteCount());

        // now be polite and wait for body arrival too (otherwise we would be
        // dropping the "line" on server)
        f.get();
        // it all should be here now
        assertEquals(true, HALF_GIG == cos.getByteCount());
        client.close();
    }

    @Test(groups = { "standalone", "default_provider" }, enabled = false)
    public void deferredSimpleWithFailure() throws IOException,
            ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = getAsyncHttpClient(getAsyncHttpClientConfig());
        AsyncHttpClient.BoundRequestBuilder r = client.prepareGet(
                "http://127.0.0.1:" + port1 + "/deferredSimpleWithFailure")
                .addHeader("X-FAIL-TRANSFER", Boolean.TRUE.toString());

        CountingOutputStream cos = new CountingOutputStream();
        BodyDeferringAsyncHandler bdah = new BodyDeferringAsyncHandler(cos);
        Future<Response> f = r.execute(bdah);
        Response resp = bdah.getResponse();
        assertNotNull(resp);
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        assertEquals(
                true,
                resp.getHeader("content-length").equals(
                        String.valueOf(HALF_GIG)));
        // we got headers only, it's probably not all yet here (we have BIG file
        // downloading)
        assertEquals(true, HALF_GIG >= cos.getByteCount());

        // now be polite and wait for body arrival too (otherwise we would be
        // dropping the "line" on server)
        try {
            f.get();
            Assert.fail("get() should fail with IOException!");
        } catch (Exception e) {
            // good
        }
        // it's incomplete, there was an error
        assertEquals(false, HALF_GIG == cos.getByteCount());
        client.close();
    }

    @Test(groups = { "standalone", "default_provider" })
    public void deferredInputStreamTrick() throws IOException,
            ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = getAsyncHttpClient(getAsyncHttpClientConfig());
        AsyncHttpClient.BoundRequestBuilder r = client
                .prepareGet("http://127.0.0.1:" + port1
                        + "/deferredInputStreamTrick");

        PipedOutputStream pos = new PipedOutputStream();
        PipedInputStream pis = new PipedInputStream(pos);
        BodyDeferringAsyncHandler bdah = new BodyDeferringAsyncHandler(pos);

        Future<Response> f = r.execute(bdah);

        BodyDeferringInputStream is = new BodyDeferringInputStream(f, bdah, pis);

        Response resp = is.getAsapResponse();
        assertNotNull(resp);
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        assertEquals(
                true,
                resp.getHeader("content-length").equals(
                        String.valueOf(HALF_GIG)));
        // "consume" the body, but our code needs input stream
        CountingOutputStream cos = new CountingOutputStream();
        copy(is, cos);

        // now we don't need to be polite, since consuming and closing
        // BodyDeferringInputStream does all.
        // it all should be here now
        assertEquals(true, HALF_GIG == cos.getByteCount());
        client.close();
    }

    @Test(groups = { "standalone", "default_provider" })
    public void deferredInputStreamTrickWithFailure() throws IOException,
            ExecutionException, TimeoutException, InterruptedException {
        AsyncHttpClient client = getAsyncHttpClient(getAsyncHttpClientConfig());
        AsyncHttpClient.BoundRequestBuilder r = client.prepareGet(
                "http://127.0.0.1:" + port1
                        + "/deferredInputStreamTrickWithFailure").addHeader(
                "X-FAIL-TRANSFER", Boolean.TRUE.toString());

        PipedOutputStream pos = new PipedOutputStream();
        PipedInputStream pis = new PipedInputStream(pos);
        BodyDeferringAsyncHandler bdah = new BodyDeferringAsyncHandler(pos);

        Future<Response> f = r.execute(bdah);

        BodyDeferringInputStream is = new BodyDeferringInputStream(f, bdah, pis);

        Response resp = is.getAsapResponse();
        assertNotNull(resp);
        assertEquals(resp.getStatusCode(), HttpServletResponse.SC_OK);
        assertEquals(
                true,
                resp.getHeader("content-length").equals(
                        String.valueOf(HALF_GIG)));
        // "consume" the body, but our code needs input stream
        CountingOutputStream cos = new CountingOutputStream();
        try {
            copy(is, cos);
            Assert.fail("InputStream consumption should fail with IOException!");
        } catch (IOException e) {
            // good!
        }
        client.close();
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testConnectionRefused() throws IOException, ExecutionException,
            TimeoutException, InterruptedException {
        int newPortWithoutAnyoneListening = findFreePort();
        AsyncHttpClient client = getAsyncHttpClient(getAsyncHttpClientConfig());
        AsyncHttpClient.BoundRequestBuilder r = client
                .prepareGet("http://127.0.0.1:" + newPortWithoutAnyoneListening
                        + "/testConnectionRefused");

        CountingOutputStream cos = new CountingOutputStream();
        BodyDeferringAsyncHandler bdah = new BodyDeferringAsyncHandler(cos);
        r.execute(bdah);
        try {
            bdah.getResponse();
            Assert.fail("IOException should be thrown here!");
        } catch (IOException e) {
            // good
        }

        client.close();
    }

}
