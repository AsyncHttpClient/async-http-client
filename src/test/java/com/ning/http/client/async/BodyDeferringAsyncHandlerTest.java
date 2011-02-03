package com.ning.http.client.async;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.BodyDeferringAsyncHandler;
import com.ning.http.client.Response;

public abstract class BodyDeferringAsyncHandlerTest extends AbstractBasicTest {

    protected static final int HALF_GIG = 536870912;

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

	    boolean wantFailure = httpRequest.getHeader("X-FAIL-TRANSFER") != null;
	    boolean wantSlow = httpRequest.getHeader("X-SLOW") != null;

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

		if (i > HALF_GIG / 2) {
		    if (wantFailure) {
			httpResponse.sendError(500);
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

    @Test(groups = { "standalone", "default_provider" })
    public void digestSimple() throws IOException, ExecutionException,
	    TimeoutException, InterruptedException {
	AsyncHttpClient client = getAsyncHttpClient(null);
	AsyncHttpClient.BoundRequestBuilder r = client
		.prepareGet("http://127.0.0.1:" + port1 + "/");

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
	// we got headers only, it's not all yet here (we have BIG file
	// downloading)
	assertEquals(false, HALF_GIG == cos.getByteCount());

	// now be polite and wait for body arrival too (otherwise we would be
	// dropping the "line" on server)
	f.get();
	// it all should be here now
	assertEquals(true, HALF_GIG == cos.getByteCount());
	client.close();
    }

    @Test(groups = { "standalone", "default_provider" })
    public void digestSimpleInputStreamTrick() throws IOException,
	    ExecutionException, TimeoutException, InterruptedException {
	AsyncHttpClient client = getAsyncHttpClient(null);
	AsyncHttpClient.BoundRequestBuilder r = client
		.prepareGet("http://127.0.0.1:" + port1 + "/");

	PipedOutputStream pos = new PipedOutputStream();
	BodyDeferringAsyncHandler bdah = new BodyDeferringAsyncHandler(pos);

	Future<Response> f = r.execute(bdah);

	InputStream is = new BodyDeferringAsyncHandler.BodyDeferringInputStream<Response>(
		f, new PipedInputStream(pos));

	Response resp = bdah.getResponse();
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
    public void digestSimpleInputStreamTrickWithFailure() throws IOException,
	    ExecutionException, TimeoutException, InterruptedException {
	AsyncHttpClient client = getAsyncHttpClient(null);
	AsyncHttpClient.BoundRequestBuilder r = client.prepareGet(
		"http://127.0.0.1:" + port1 + "/").addHeader("X-FAIL-TRANSFER",
		Boolean.TRUE.toString());

	PipedOutputStream pos = new PipedOutputStream();
	BodyDeferringAsyncHandler bdah = new BodyDeferringAsyncHandler(pos);

	Future<Response> f = r.execute(bdah);

	InputStream is = new BodyDeferringAsyncHandler.BodyDeferringInputStream<Response>(
		f, new PipedInputStream(pos));

	Response resp = bdah.getResponse();
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

}
