package com.ning.http.client;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;

import com.ning.http.client.Response.ResponseBuilder;

/**
 * An AsyncHandler that returns Response (without body, so status code and
 * headers only) as fast as possible for inspection, but leaves you the option
 * to defer body consumption.
 * <p>
 * This class introduces new call: getResponse(), that blocks caller thread as
 * long as headers are received, and return Response as soon as possible, but
 * still pouring response body into supplied output stream. This handler is
 * meant for situations when the "recommended" way (using
 * <code>client.prepareGet("http://foo.com/aResource").execute().get()</code>
 * would not work for you, since a potentially large response body is about to
 * be GETted, but you need headers first, or you don't know yet (depending on
 * some logic, maybe coming from headers) where to save the body, or you just
 * want to leave body stream to some other component to consume it.
 * <p>
 * All these above means that this AsyncHandler needs a bit of different
 * handling than "recommended" way. Some examples:
 * 
 * <pre>
 *     FileOutputStream fos = ...
 *     BodyDeferringAsyncHandler bdah = new BodyDeferringAsyncHandler(fos);
 *     // client executes async
 *     Future&lt;Response&gt; fr = client.prepareGet(&quot;http://foo.com/aresource&quot;).execute(
 * 	bdah);
 *     // main thread will block here until headers are available
 *     Response response = bdah.getResponse();
 *     // you can continue examine headers while actual body download happens
 *     // in separate thread
 *     // ...
 *     // finally &quot;join&quot; the download
 *     fr.get();
 * </pre>
 * 
 * <pre>
 *     PipedOutputStream pout = new PipedOutputStream();
 *     BodyDeferringAsyncHandler bdah = new BodyDeferringAsyncHandler(pout);
 *     // client executes async
 *     Future&lt;Response&gt; fr = client.prepareGet(&quot;http://foo.com/aresource&quot;).execute(bdah);
 *     // main thread will block here until headers are available
 *     Response response = bdah.getResponse();
 *     if (response.getStatusCode() == 200) {
 *      InputStream pin = new BodyDeferringInputStream(fr,new PipedInputStream(pout));
 *      // consume InputStream
 *      ...
 *     } else {
 *      // handle unexpected response status code
 *      ...
 *     }
 * </pre>
 * 
 */
public class BodyDeferringAsyncHandler implements AsyncHandler<Response> {
    private final ResponseBuilder responseBuilder = new ResponseBuilder();

    private final CountDownLatch headersArrived = new CountDownLatch(1);

    private final OutputStream output;

    private volatile boolean responseSet;

    private volatile Response response;

    private volatile Throwable t;

    public BodyDeferringAsyncHandler(final OutputStream os) {
	this.output = os;
	this.responseSet = false;
    }

    public void onThrowable(Throwable t) {
	this.t = t;
    }

    public STATE onStatusReceived(HttpResponseStatus responseStatus)
	    throws Exception {
	responseBuilder.reset();
	responseBuilder.accumulate(responseStatus);
	return STATE.CONTINUE;
    }

    public STATE onHeadersReceived(HttpResponseHeaders headers)
	    throws Exception {
	responseBuilder.accumulate(headers);
	return STATE.CONTINUE;
    }

    public STATE onBodyPartReceived(HttpResponseBodyPart bodyPart)
	    throws Exception {
	// body arrived, flush headers
	if (!responseSet) {
	    response = responseBuilder.build();
	    responseSet = true;
	    headersArrived.countDown();
	}

	bodyPart.writeTo(output);
	return STATE.CONTINUE;
    }

    public Response onCompleted() throws IOException {
	// Counting down to handle error cases too.
	// In "normal" cases, latch is already at 0 here
	// But in other cases, for example when because of some error
	// onBodyPartReceived() is never called, the caller
	// of getResponse() would remain blocked infinitely.
	// By contract, onCompleted() is always invoked, even in case of errors
	headersArrived.countDown();

	try {
	    output.flush();
	} finally {
	    output.close();
	}

	if (t != null) {
	    IOException ioe = new IOException(t.getMessage());
	    ioe.initCause(t);
	    throw ioe;
	} else {
	    // sending out current response
	    return responseBuilder.build();
	}
    }

    /**
     * This method -- unlike Future<Reponse>.get() -- will block only as long,
     * as headers arrive. This is useful for large transfers, to examine headers
     * ASAP, and defer body streaming to it's fine destination and prevent
     * unneeded bandwidth consumption. The response here will contain the very
     * 1st response from server, so status code and headers, but it might be
     * incomplete in case of broken servers sending trailing headers. In that
     * case, the "usual" Future<Response>.get() method will return complete
     * headers, but multiple invocations of getResponse() will always return the
     * 1st cached, probably incomplete one. Note: the response returned by this
     * method will contain everything <em>except</em> the response body itself,
     * so invoking any method like Response.getResponseBodyXXX() will result in
     * error!
     * 
     * @return
     * @throws InterruptedException
     */
    public Response getResponse() throws InterruptedException {
	// block here as long as headers arrive
	headersArrived.await();
	return response;
    }

    // ==

    public static class BodyDeferringInputStream<T> extends FilterInputStream {
	private final Future<T> future;

	public BodyDeferringInputStream(final Future<T> future,
		final InputStream in) {
	    super(in);
	    this.future = future;
	}

	public void close() throws IOException {
	    // close
	    super.close();
	    // join
	    get();
	}

	public T get() throws IOException {
	    try {
		return future.get();
	    } catch (Exception e) {
		IOException ioe = new IOException(e.getMessage());
		ioe.initCause(e);
		throw ioe;
	    }
	}
    }
}