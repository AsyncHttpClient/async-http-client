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
 *
 */
package com.ning.http.client.resumable;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.ning.http.client.Response.ResponseBuilder;
import com.ning.http.client.listener.TransferCompletionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An {@link AsyncHandler} which support resumable download, e.g when used with an {@link ResumableIOExceptionFilter},
 * this handler can resume the download operation at the point it was before the interruption occured. This prevent having to
 * download the entire file again. It's the responsibility of the {@link com.ning.http.client.listener.TransferListener}
 * to track how many bytes has been transferred and to properly adjust the file's write position.
 * <p/>
 * In case of a JVM crash/shutdown, you can create an instance of this class and pass the last valid bytes position.
 */
public class ResumableAsyncHandler<T> implements AsyncHandler<T> {
    private final static Logger logger = LoggerFactory.getLogger(TransferCompletionHandler.class);
    private final AtomicLong byteTransferred;
    private Integer contentLength;
    private String url;
    private final ResumableProcessor resumableProcessor;
    private final AsyncHandler<T> decoratedAsyncHandler;
    private final ConcurrentLinkedQueue<ResumableListener> listeners = new ConcurrentLinkedQueue<ResumableListener>();
    private static Map<String, Long> resumableIndex;
    private final static ResumableIndexThread resumeIndexThread = new ResumableIndexThread();
    private ResponseBuilder responseBuilder = new ResponseBuilder();

    private ResumableAsyncHandler(long byteTransferred, ResumableProcessor resumableProcessor, AsyncHandler<T> decoratedAsyncHandler) {
        this.byteTransferred = new AtomicLong(byteTransferred);

        if (resumableProcessor == null) {
            resumableProcessor = new NULLResumableHandler();
        }
        this.resumableProcessor = resumableProcessor;

        resumableIndex = resumableProcessor.load();
        resumeIndexThread.addResumableProcessor(resumableProcessor);

        this.decoratedAsyncHandler = decoratedAsyncHandler;
    }

    public ResumableAsyncHandler(long byteTransferred) {
        this(byteTransferred, null, null);
    }

    public ResumableAsyncHandler() {
        this(0);
    }

    public ResumableAsyncHandler(AsyncHandler<T> decoratedAsyncHandler) {
        this(0, new PropertiesBasedResumableProcessor(), decoratedAsyncHandler);
    }

    public ResumableAsyncHandler(ResumableProcessor resumableProcessor) {
        this(0, resumableProcessor, null);
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public AsyncHandler.STATE onStatusReceived(final HttpResponseStatus status) throws Exception {
        responseBuilder.accumulate(status);
        if (status.getStatusCode() == 200) {
            url = status.getUrl().toURL().toString();
        } else {
            return AsyncHandler.STATE.ABORT;            
        }

        if (decoratedAsyncHandler != null) {
            return decoratedAsyncHandler.onStatusReceived(status);
        }

        return AsyncHandler.STATE.CONTINUE;
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public void onThrowable(Throwable t) {
        if (decoratedAsyncHandler != null) {
            decoratedAsyncHandler.onThrowable(t);
        }
        logger.error("", t);
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public AsyncHandler.STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
        byteTransferred.addAndGet(bodyPart.getBodyPartBytes().length);
        resumableProcessor.put(url, byteTransferred.get());
        fireOnBytesReceived(bodyPart.getBodyByteBuffer());
        
        if (decoratedAsyncHandler != null) {
            return decoratedAsyncHandler.onBodyPartReceived(bodyPart);
        }
        return STATE.CONTINUE;
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public T onCompleted() throws Exception {
        resumableProcessor.remove(url);
        fireOnComplete();

        if (decoratedAsyncHandler != null) {
            decoratedAsyncHandler.onCompleted();
        }
        // Not sure
        return (T) responseBuilder.build();
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public AsyncHandler.STATE onHeadersReceived(HttpResponseHeaders headers) throws Exception {
        responseBuilder.accumulate(headers);        
        if (headers.getHeaders().getFirstValue("Content-Length") != null) {
            contentLength = Integer.valueOf(headers.getHeaders().getFirstValue("Content-Length"));
            if (contentLength == null || contentLength == -1) {
                return AsyncHandler.STATE.ABORT;
            }
        }

        if (decoratedAsyncHandler != null) {
            return decoratedAsyncHandler.onHeadersReceived(headers);
        }
        return AsyncHandler.STATE.CONTINUE;
    }

    /**
     * Invoke this API if you want to set the Range header on your {@link Request} based on the last valid bytes
     * position.
     *
     * @param request {@link Request}
     * @return a {@link Request} with the Range header properly set.
     */
    public Request adjustRequestRange(Request request) {

        if (resumableIndex.get(request.getUrl()) != null) {
            byteTransferred.set(resumableIndex.get(request.getUrl()));
        }

        RequestBuilder builder = new RequestBuilder(request);
        builder.setHeader("Range", "bytes=" + byteTransferred.get() + "-");
        return builder.build();
    }
    private void fireOnBytesReceived(ByteBuffer byteBuffer) throws IOException {
        for (ResumableListener l : listeners) {
            l.onBytesReceived(byteBuffer);
        }
    }

    private void fireOnComplete() {
        for (ResumableListener l : listeners) {
            l.onAllBytesReceived();
        }
    }

     /**
     * Add a {@link ResumableListener}
     * @param t a {@link ResumableListener}
     * @return this
     */
    public ResumableAsyncHandler addResumableListener(ResumableListener t) {
        listeners.offer(t);
        return this;
    }

    /**
     * Remove a {@link ResumableListener}
     * @param t a {@link ResumableListener}
     * @return this
     */
    public ResumableAsyncHandler removeResumableListener(ResumableListener t) {
        listeners.remove(t);
        return this;
    }


    private static class ResumableIndexThread extends Thread {

        public final ConcurrentLinkedQueue<ResumableProcessor> resumableProcessors = new ConcurrentLinkedQueue<ResumableProcessor>();

        public ResumableIndexThread() {
            Runtime.getRuntime().addShutdownHook(this);
        }

        public void addResumableProcessor(ResumableProcessor p) {
            resumableProcessors.offer(p);
        }

        public void run() {
            for (ResumableProcessor p : resumableProcessors) {
                p.save(resumableIndex);
            }
        }
    }

    /**
     * An interface to implement in order to manage the way the incomplete file management are handled.
     */
    public static interface ResumableProcessor {

        /**
         * Associate a key with the number of bytes sucessfully transferred.
         *
         * @param key              a key. The recommended way is to use an url.
         * @param transferredBytes The number of bytes sucessfully transferred.
         */
        public void put(String key, long transferredBytes);

        /**
         * Remove the key associate value.
         *
         * @param key key from which the value will be discarted
         */
        public void remove(String key);

        /**
         * Save the current {@link Map} instance which contains information about the current transfer state.
         * This method *only* invoked when the JVM is shutting down.
         *
         * @param map
         */
        public void save(Map<String, Long> map);

        /**
         * Load the {@link Map} in memory, contains information about the transferred bytes.
         *
         * @return {@link Map}
         */
        public Map<String, Long> load();

    }

    private static class NULLResumableHandler implements ResumableProcessor {

        public void put(String url, long transferredBytes) {
        }

        public void remove(String uri) {
        }

        public void save(Map<String, Long> map) {
        }

        public Map<String, Long> load() {
            return new HashMap<String, Long>();
        }
    }
}
