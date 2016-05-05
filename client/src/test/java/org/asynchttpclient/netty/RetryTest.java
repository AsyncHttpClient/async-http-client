package org.asynchttpclient.netty;

import org.asynchttpclient.*;
import org.asynchttpclient.handler.ExtendedAsyncHandler;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

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
                        .setRequestTimeout(100 * 6000)
                        .setMaxRequestRetry(maxRequestRetry)
                        .setMaxConnections(10)
                        .setKeepAlive(true)
                        .setExpBackoffRetryInitialInterval(initialValue)
                        .setExpBackoffRetryMaxInterval(maxValue)
                        .setExpBackoffRetryMultiplier(multipler)
                        .setExpBackoffRetryEnabled(true)
                        .build();

        final AsyncRetryHandler handler = new AsyncRetryHandler();
        final AtomicBoolean stoped = new AtomicBoolean(false);
        final InputStream inputStream = new InputStream() {

            @Override
            public int read() throws IOException {
                try {

                    if (stoped.compareAndSet(false, true)) {
                        server.setStopTimeout(1000L);
                        server.stop();
                    }

                    Thread.sleep(1000L);

                } catch(Exception e) {
                    e.printStackTrace();
                }

                return 0xFF;
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
                        .setRequestTimeout(100 * 6000)
                        .setMaxRequestRetry(maxRequestRetry)
                        .setMaxConnections(10)
                        .setKeepAlive(true)
                        .setExpBackoffRetryInitialInterval(initialValue)
                        .setExpBackoffRetryMaxInterval(maxValue)
                        .setExpBackoffRetryMultiplier(multipler)
                        .setExpBackoffRetryEnabled(true)
                        .build();

        final AsyncRetryHandler handler = new AsyncRetryHandler();

        try (final AsyncHttpClient httpClient = new DefaultAsyncHttpClient(config)) {
            httpClient.prepareGet("http://10.255.255.255").execute(handler).get();
        }catch(ExecutionException e) {
            throw e.getCause();
        } finally {
            Assert.assertEquals(handler.retryCount, maxRequestRetry);
        }

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
