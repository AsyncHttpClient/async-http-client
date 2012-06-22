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
package com.ning.http.client.listener;

import com.ning.http.client.AsyncCompletionHandlerBase;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link com.ning.http.client.AsyncHandler} that can be used to notify a set of {@link com.ning.http.client.listener.TransferListener}
 * <p/>
 * <blockquote><pre>
 * AsyncHttpClient client = new AsyncHttpClient();
 * TransferCompletionHandler tl = new TransferCompletionHandler();
 * tl.addTransferListener(new TransferListener() {
 * <p/>
 * public void onRequestHeadersSent(FluentCaseInsensitiveStringsMap headers) {
 * }
 * <p/>
 * public void onResponseHeadersReceived(FluentCaseInsensitiveStringsMap headers) {
 * }
 * <p/>
 * public void onBytesReceived(ByteBuffer buffer) {
 * }
 * <p/>
 * public void onBytesSent(ByteBuffer buffer) {
 * }
 * <p/>
 * public void onRequestResponseCompleted() {
 * }
 * <p/>
 * public void onThrowable(Throwable t) {
 * }
 * });
 * <p/>
 * Response response = httpClient.prepareGet("http://...").execute(tl).get();
 * </pre></blockquote>
 */
public class TransferCompletionHandler extends AsyncCompletionHandlerBase {
    private final static Logger logger = LoggerFactory.getLogger(TransferCompletionHandler.class);
    private final ConcurrentLinkedQueue<TransferListener> listeners = new ConcurrentLinkedQueue<TransferListener>();
    private final boolean accumulateResponseBytes;
    private TransferAdapter transferAdapter;
    private AtomicLong bytesTransferred = new AtomicLong();
    private AtomicLong totalBytesToTransfer = new AtomicLong(0);

    /**
     * Create a TransferCompletionHandler that will not accumulate bytes. The resulting {@link com.ning.http.client.Response#getResponseBody()},
     * {@link com.ning.http.client.Response#getResponseBodyAsStream()} and {@link Response#getResponseBodyExcerpt(int)} will
     * throw an IllegalStateException if called.
     */
    public TransferCompletionHandler() {
        this(false);
    }

    /**
     * Create a TransferCompletionHandler that can or cannot accumulate bytes and make it available when
     * {@link com.ning.http.client.Response#getResponseBody()} get called. The default is false.
     *
     * @param accumulateResponseBytes true to accumulates bytes in memory.
     */
    public TransferCompletionHandler(boolean accumulateResponseBytes) {
        this.accumulateResponseBytes = accumulateResponseBytes;
    }

    /**
     * Add a {@link com.ning.http.client.listener.TransferListener}
     *
     * @param t a {@link com.ning.http.client.listener.TransferListener}
     * @return this
     */
    public TransferCompletionHandler addTransferListener(TransferListener t) {
        listeners.offer(t);
        return this;
    }

    /**
     * Remove a {@link com.ning.http.client.listener.TransferListener}
     *
     * @param t a {@link com.ning.http.client.listener.TransferListener}
     * @return this
     */
    public TransferCompletionHandler removeTransferListener(TransferListener t) {
        listeners.remove(t);
        return this;
    }

    /**
     * Associate a {@link com.ning.http.client.listener.TransferCompletionHandler.TransferAdapter} with this listener.
     *
     * @param transferAdapter {@link TransferAdapter}
     */
    public void transferAdapter(TransferAdapter transferAdapter) {
        this.transferAdapter = transferAdapter;
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public STATE onHeadersReceived(final HttpResponseHeaders headers) throws Exception {
        fireOnHeaderReceived(headers.getHeaders());
        return super.onHeadersReceived(headers);
    }

    @Override
    public STATE onBodyPartReceived(final HttpResponseBodyPart content) throws Exception {
        STATE s = STATE.CONTINUE;
        if (accumulateResponseBytes) {
            s = super.onBodyPartReceived(content);
        }
        fireOnBytesReceived(content.getBodyPartBytes());
        return s;
    }

    @Override
    public Response onCompleted(Response response) throws Exception {
        fireOnEnd();
        return response;
    }

    /**
     * {@inheritDoc}
     */
    public STATE onHeaderWriteCompleted() {
        List<String> list = transferAdapter.getHeaders().get("Content-Length");
        if (list != null && list.size() > 0 && list.get(0) != "") {
            totalBytesToTransfer.set(Long.valueOf(list.get(0)));
        }

        fireOnHeadersSent(transferAdapter.getHeaders());
        return STATE.CONTINUE;
    }

    /**
     * {@inheritDoc}
     */
    public STATE onContentWriteCompleted() {
        return STATE.CONTINUE;
    }

    /**
     * {@inheritDoc}
     */
    public STATE onContentWriteProgress(long amount, long current, long total) {
        if (bytesTransferred.get() == -1) {
            return STATE.CONTINUE;
        }

        if (totalBytesToTransfer.get() == 0) {
            totalBytesToTransfer.set(total);
        }

        // We need to track the count because all is asynchronous and Netty may not invoke us on time.
        bytesTransferred.addAndGet(amount);

        if (transferAdapter != null) {
            byte[] bytes = new byte[(int) (amount)];
            transferAdapter.getBytes(bytes);
            fireOnBytesSent(bytes);
        }
        return STATE.CONTINUE;
    }


    @Override
    public void onThrowable(Throwable t) {
        fireOnThrowable(t);
    }

    private void fireOnHeadersSent(FluentCaseInsensitiveStringsMap headers) {
        for (TransferListener l : listeners) {
            try {
                l.onRequestHeadersSent(headers);
            } catch (Throwable t) {
                l.onThrowable(t);
            }
        }
    }

    private void fireOnHeaderReceived(FluentCaseInsensitiveStringsMap headers) {
        for (TransferListener l : listeners) {
            try {
                l.onResponseHeadersReceived(headers);
            } catch (Throwable t) {
                l.onThrowable(t);
            }
        }
    }

    private void fireOnEnd() {
        // There is a probability that the asynchronous listener never gets called, so we fake it at the end once
        // we are 100% sure the response has been received.
        long count = bytesTransferred.getAndSet(-1);
        if (count != totalBytesToTransfer.get()) {
            if (transferAdapter != null) {
                byte[] bytes = new byte[8192];
                int leftBytes = (int) (totalBytesToTransfer.get() - count);
                int length = 8192;
                while (leftBytes > 0) {
                    if (leftBytes > 8192) {
                        leftBytes -= 8192;
                    } else {
                        length = leftBytes;
                        leftBytes = 0;
                    }

                    if (length < 8192) {
                        bytes = new byte[length];
                    }

                    transferAdapter.getBytes(bytes);
                    fireOnBytesSent(bytes);
                }
            }
        }

        for (TransferListener l : listeners) {
            try {
                l.onRequestResponseCompleted();
            } catch (Throwable t) {
                l.onThrowable(t);
            }
        }
    }

    private void fireOnBytesReceived(byte[] b) {
        for (TransferListener l : listeners) {
            try {
                l.onBytesReceived(ByteBuffer.wrap(b));
            } catch (Throwable t) {
                l.onThrowable(t);
            }
        }
    }

    private void fireOnBytesSent(byte[] b) {
        for (TransferListener l : listeners) {
            try {
                l.onBytesSent(ByteBuffer.wrap(b));
            } catch (Throwable t) {
                l.onThrowable(t);
            }
        }
    }

    private void fireOnThrowable(Throwable t) {
        for (TransferListener l : listeners) {
            try {
                l.onThrowable(t);
            } catch (Throwable t2) {
                logger.warn("onThrowable", t2);
            }
        }
    }

    public abstract static class TransferAdapter {
        private final FluentCaseInsensitiveStringsMap headers;

        public TransferAdapter(FluentCaseInsensitiveStringsMap headers) throws IOException {
            this.headers = headers;
        }

        public FluentCaseInsensitiveStringsMap getHeaders() {
            return headers;
        }

        public abstract void getBytes(byte[] bytes);
    }
}
