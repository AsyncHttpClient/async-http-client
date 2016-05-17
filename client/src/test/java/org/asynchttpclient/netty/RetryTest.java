package org.asynchttpclient.netty;

import org.asynchttpclient.*;
import org.asynchttpclient.handler.ExponentialRetryHandler;
import org.asynchttpclient.handler.ExtendedAsyncHandler;
import org.asynchttpclient.handler.RetryHandler;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.asynchttpclient.Dsl.asyncHttpClient;

public class RetryTest extends AbstractBasicTest {

    @Test(groups = "standalone",expectedExceptions = java.net.ConnectException.class, timeOut = 60000)
    public void testRetryOnChannelInactive() throws Throwable {
        final int initialValue = 10;
        final int maxValue = 100;
        final float multipler = 2.0f;
        final int maxRequestRetry = 3;
        final AsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                        .setReadTimeout(150)
                        .setRequestTimeout(10 * 6000)
                        .setMaxRequestRetry(maxRequestRetry)
                        .setMaxConnections(10)
                        .setKeepAlive(true)
                        .setExpBackoffRetryInitialInterval(initialValue)
                        .setExpBackoffRetryMaxInterval(maxValue)
                        .setExpBackoffRetryMultiplier(multipler)
                        .setExpBackoffRetryEnabled(true)
                        .build();

        final AsyncRetryHandler<Void> handler = new AsyncRetryHandler<>(
                        new ExponentialRetryHandler(initialValue, maxValue, multipler));
        final AtomicBoolean stoped = new AtomicBoolean(false);
        final InputStream inputStream = new InputStream() {

            @Override
            public int read() throws IOException {
                try {

                    if (stoped.compareAndSet(false, true)) {
                        server.setStopTimeout(1000L);
                        server.stop();
                    }

                } catch(Exception e) {
                    e.printStackTrace();
                }

                return -1;
            }
        };
        try (AsyncHttpClient client = asyncHttpClient(config)) {
            client.preparePut(getTargetUrl()).setBody(inputStream).execute(handler).get();
        } catch (ExecutionException e) {
            throw e.getCause();
        } finally {
            Assert.assertEquals(handler.retryCount, maxRequestRetry);
        }

    }

    @Test(groups = "standalone",expectedExceptions = java.net.ConnectException.class, timeOut = 60000)
    public void testRetryOnConnectionException() throws Throwable {
        final int initialValue = 10;
        final int maxValue = 100;
        final float multipler = 2.0f;
        final int maxRequestRetry = 3;
        final AsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder()
                        .setRequestTimeout(10 * 6000)
                        .setMaxRequestRetry(maxRequestRetry)
                        .setMaxConnections(10)
                        .setKeepAlive(true)
                        .setExpBackoffRetryInitialInterval(initialValue)
                        .setExpBackoffRetryMaxInterval(maxValue)
                        .setExpBackoffRetryMultiplier(multipler)
                        .setExpBackoffRetryEnabled(true)
                        .build();

        final AsyncRetryHandler<Void> handler = new AsyncRetryHandler<>(
                        new ExponentialRetryHandler(initialValue, maxValue, multipler));

        try (final AsyncHttpClient httpClient = new DefaultAsyncHttpClient(config)) {
            httpClient.prepareGet("http://10.255.255.255").execute(handler).get();
        }catch(ExecutionException e) {
            throw e.getCause();
        } finally {
            Assert.assertEquals(handler.retryCount, maxRequestRetry);
        }

    }

    class AsyncRetryHandler<T> extends ExtendedAsyncHandler<T> {

        final AtomicLong lastRetryTime = new AtomicLong(System.currentTimeMillis());
        final RetryHandler retryHandler;

        volatile int retryCount;

        public AsyncRetryHandler(RetryHandler retryHandler) {
            this.retryHandler = retryHandler;
        }

        @Override
        public void onRetry() {
            retryCount++;

            final long currentTimeInMs = System.currentTimeMillis();
            final long lastTimeMs = lastRetryTime.getAndSet(currentTimeInMs);
            Assert.assertTrue(currentTimeInMs - lastTimeMs >= retryHandler.nextRetryMillis());
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
