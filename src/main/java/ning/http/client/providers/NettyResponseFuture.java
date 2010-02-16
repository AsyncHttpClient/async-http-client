/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package ning.http.client.providers;

import ning.http.client.AsyncHandler;
import ning.http.client.AsyncHttpClient;
import ning.http.client.FutureImpl;
import ning.http.client.Request;
import ning.http.client.Response;
import ning.http.url.Url;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.jboss.netty.handler.codec.http.HttpRequest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Future} that can be used to track when an asynchronous HTTP request has been fully processed.
 *
 * @param <V>
 */
public final class NettyResponseFuture<V> implements FutureImpl {

    private final static Logger log = LogManager.getLogger(AsyncHttpClient.class);
    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicBoolean isDone = new AtomicBoolean(false);
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private final NettyAsyncResponse asyncResponse;
    private final AsyncHandler<V> asyncHandler;
    private final long responseTimeoutInMs;
    private final Request request;
    private final HttpRequest nettyRequest;
    private final AtomicReference<V> content = new AtomicReference<V>();

    public NettyResponseFuture(Url  url,Request request, AsyncHandler asyncHandler,
                               HttpRequest nettyRequest,long responseTimeoutInMs) {

        this.asyncResponse = new NettyAsyncResponse<V>(url);
        asyncResponse.setFuture(this);

        this.asyncHandler = (AsyncHandler) asyncHandler;
        this.responseTimeoutInMs = responseTimeoutInMs;
        this.request = request;
        this.nettyRequest = nettyRequest;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isDone() {
        return isDone.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isCancelled() {
        return isCancelled.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean cancel(boolean force) {
        latch.countDown();
        isCancelled.set(true);
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V get() throws InterruptedException {
        try{
            if (!isDone() && !isCancelled()) {
                if (!latch.await(responseTimeoutInMs, TimeUnit.MILLISECONDS)) {
                    isCancelled.set(true);
                    TimeoutException te = new TimeoutException("No response received");
                    onThrowable(te);
                    throw te;
                }
                isDone.set(true);
            }
            return (V) getContent();
        } catch (TimeoutException ex) {
            /**
             * To prevent deadlock, we still throw an exception, but a Runtime one to fulfill the API contract.
             */
            throw new RuntimeException(ex);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Response get(long l, TimeUnit tu) throws InterruptedException, TimeoutException {
        if (!isDone() && !isCancelled()) {
            if (!latch.await(l, tu)) {
                isCancelled.set(true);
                TimeoutException te = new TimeoutException("No response received");
                onThrowable(te);
                throw te;
            }
        }

        return (Response) getContent();
    }

    public void onThrowable(Throwable t) {
        asyncHandler.onThrowable(t);
    }

    V getContent() {
        if (content.get() == null) {
            try {
                content.set(asyncHandler.onCompleted(asyncResponse));
            } catch (Throwable ex) {
                onThrowable(ex);
                throw new RuntimeException(ex);
            }
        }
        return content.get();
    }

    public void done() {
        getContent();
        latch.countDown();
    }

    public Request getRequest() {
        return request;
    }

    public NettyAsyncResponse getAsyncResponse() {
        return asyncResponse;
    }

    public HttpRequest getNettyRequest() {
        return nettyRequest;
    }

    public AsyncHandler<V> getAsyncHandler() {
        return asyncHandler;
    }
}
