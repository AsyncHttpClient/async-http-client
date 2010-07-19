package com.ning.http.client.async;


import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Tests default asynchronous life cycle.
 *
 * @author Hubert Iwaniuk
 */
public class AsyncStreamLifecycleTest extends AbstractBasicTest {
    private ExecutorService executorService = Executors.newFixedThreadPool(2);

    @BeforeClass
    @Override
    public void setUpGlobal() throws Exception {
        super.setUpGlobal();
    }

    @AfterClass
    @Override
    public void tearDownGlobal() throws Exception {
        super.tearDownGlobal();
        executorService.shutdownNow();
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new AbstractHandler() {
            public void handle(String s, Request request, HttpServletRequest req, final HttpServletResponse resp)
                    throws IOException, ServletException {
                resp.setContentType("text/plain;charset=utf-8");
                resp.setStatus(200);
                final Continuation continuation = ContinuationSupport.getContinuation(req);
                continuation.suspend();
                final PrintWriter writer = resp.getWriter();
                executorService.submit(new Runnable() {
                    public void run() {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            log.error("Failed to sleep for 100 ms.", e);
                        }
                        log.info("Delivering part1.");
                        writer.write("part1");
                        writer.flush();
                    }
                });
                executorService.submit(new Runnable() {
                    public void run() {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            log.error("Failed to sleep for 200 ms.", e);
                        }
                        log.info("Delivering part2.");
                        writer.write("part2");
                        writer.flush();
                        continuation.complete();
                    }
                });
                request.setHandled(true);
            }
        };
    }

    @Test(groups = "standalone")
    public void testStream() throws IOException {
        AsyncHttpClient ahc = new AsyncHttpClient();
        final AtomicBoolean err = new AtomicBoolean(false);
        final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<String>();
        final AtomicBoolean status = new AtomicBoolean(false);
        final AtomicInteger headers = new AtomicInteger(0);
        final CountDownLatch latch = new CountDownLatch(1);
        ahc.executeRequest(ahc.prepareGet(getTargetUrl()).build(), new AsyncHandler<Object>() {
            public void onThrowable(Throwable t) {
                fail("Got throwable.", t);
                err.set(true);
            }

            public STATE onBodyPartReceived(HttpResponseBodyPart e) throws Exception {
                String s = new String(e.getBodyPartBytes());
                log.info("got part: " + s);
                if (s.isEmpty()) {
                    //noinspection ThrowableInstanceNeverThrown
                    log.warn("Sampling stacktrace.",
                            new Throwable("trace that, we should not get called for empty body."));
                }
                queue.put(s);
                return STATE.CONTINUE;
            }

            public STATE onStatusReceived(HttpResponseStatus e) throws Exception {
                status.set(true);
                return STATE.CONTINUE;
            }

            public STATE onHeadersReceived(HttpResponseHeaders e) throws Exception {
                if (headers.incrementAndGet() == 2) {
                    throw new Exception("Analyze this.");
                }
                return STATE.CONTINUE;
            }

            public Object onCompleted() throws Exception {
                latch.countDown();
                return null;
            }
        });
        try {
            assertTrue(latch.await(1, TimeUnit.SECONDS), "Latch failed.");
        } catch (InterruptedException e) {
            fail("Interrupted.", e);
        }
        assertFalse(err.get());
        assertEquals(queue.size(), 2);
        assertTrue(queue.contains("part1"));
        assertTrue(queue.contains("part2"));
        assertTrue(status.get());
        assertEquals(headers.get(), 1);
    }
}
