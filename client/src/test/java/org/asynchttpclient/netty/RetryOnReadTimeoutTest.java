package org.asynchttpclient.netty;

import org.asynchttpclient.*;
import org.asynchttpclient.handler.ExtendedAsyncHandler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.Assert;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static org.asynchttpclient.Dsl.asyncHttpClient;

public class RetryOnReadTimeoutTest extends AbstractBasicTest {

    final int readTimeout = 100;

    @Test(groups = "standalone",expectedExceptions = java.util.concurrent.TimeoutException.class, timeOut = 60000)
    public void testRetryOnReadTimeout() throws Throwable {
        final int initialValue = 10;
        final int maxValue = 100;
        final float multipler = 2.0f;
        final int maxRequestRetry = 3;

        final AsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                        .setRequestTimeout(100 * 6000)
                        .setReadTimeout(readTimeout)
                        .setMaxRequestRetry(maxRequestRetry)
                        .setMaxConnections(10)
                        .setKeepAlive(true)
                        .setExpBackoffRetryInitialInterval(initialValue)
                        .setExpBackoffRetryMaxInterval(maxValue)
                        .setExpBackoffRetryMultiplier(multipler)
                        .setExpBackoffRetryEnabled(true)
                        .build();

        final AsyncRetryHandler<Void> handler = new AsyncRetryHandler<>();

        try (AsyncHttpClient client = asyncHttpClient(config)) {
            client.prepareGet(getTargetUrl()).execute(handler).get();
        } catch (ExecutionException e) {
            throw e.getCause();
        } finally {
            Assert.assertEquals(handler.retryCount, maxRequestRetry);
        }
    }

    public AbstractHandler configureHandler() throws Exception {
        return new AbstractHandler() {

            @Override
            public void handle(String s, Request request, HttpServletRequest httpServletRequest,
                            HttpServletResponse httpServletResponse)
                            throws IOException, ServletException {

                try {
                    Thread.sleep(readTimeout * 10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                httpServletResponse.setStatus(200);
            }
        };
    }

    class AsyncRetryHandler<T> extends ExtendedAsyncHandler<T> {

        volatile int retryCount;

        @Override
        public void onRetry() {
            retryCount++;
        }

        @Override public void onThrowable(Throwable t) {}

        @Override public State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
            Assert.fail();
            return State.CONTINUE;
        }

        @Override public State onStatusReceived(HttpResponseStatus responseStatus)
                        throws Exception {
            Assert.fail();
            return State.CONTINUE;
        }

        @Override public State onHeadersReceived(HttpResponseHeaders headers) throws Exception {
            Assert.fail();
            return State.CONTINUE;
        }

        @Override public T onCompleted() throws Exception {
            Assert.fail();
            return null;
        }
    }

}
