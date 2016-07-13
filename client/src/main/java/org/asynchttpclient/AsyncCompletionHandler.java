/*
 * Copyright 2010 Ning, Inc.
 *
 * This program is licensed to you under the Apache License, version 2.0
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
package org.asynchttpclient;

import org.asynchttpclient.handler.ProgressAsyncHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link AsyncHandler} augmented with an {@link #onCompleted(Response)} convenience method which gets called
 * when the {@link Response} processing is finished.  This class also implement the {@link ProgressAsyncHandler} callback,
 * all doing nothing except returning {@link org.asynchttpclient.AsyncHandler.State#CONTINUE}
 *
 * @param <T> Type of the value that will be returned by the associated {@link java.util.concurrent.Future}
 */
public abstract class AsyncCompletionHandler<T> implements AsyncHandler<T>, ProgressAsyncHandler<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncCompletionHandler.class);
    private final Response.ResponseBuilder builder = new Response.ResponseBuilder();

    /**
     * {@inheritDoc}
     */
    public State onBodyPartReceived(final HttpResponseBodyPart content) throws Exception {
        builder.accumulate(content);
        return State.CONTINUE;
    }

    /**
     * {@inheritDoc}
     */
    public State onStatusReceived(final HttpResponseStatus status) throws Exception {
        builder.reset();
        builder.accumulate(status);
        return State.CONTINUE;
    }

    /**
     * {@inheritDoc}
     */
    public State onHeadersReceived(final HttpResponseHeaders headers) throws Exception {
        builder.accumulate(headers);
        return State.CONTINUE;
    }

    /**
     * {@inheritDoc}
     */
    public final T onCompleted() throws Exception {
        return onCompleted(builder.build());
    }

    /**
     * {@inheritDoc}
     */
    public void onThrowable(Throwable t) {
        LOGGER.debug(t.getMessage(), t);
    }

    /**
     * Invoked once the HTTP response processing is finished.
     *
     * @param response The {@link Response}
     * @return T Value that will be returned by the associated {@link java.util.concurrent.Future}
     * @throws Exception if something wrong happens
     */
    abstract public T onCompleted(Response response) throws Exception;

    /**
     * Invoked when the content (a {@link java.io.File}, {@link String} or {@link java.io.FileInputStream} has been fully
     * written on the I/O socket.
     *
     * @return a {@link org.asynchttpclient.AsyncHandler.State} telling to CONTINUE or ABORT the current processing.
     */
    public State onHeadersWritten() {
        return State.CONTINUE;
    }

    /**
     * Invoked when the content (a {@link java.io.File}, {@link String} or {@link java.io.FileInputStream} has been fully
     * written on the I/O socket.
     *
     * @return a {@link org.asynchttpclient.AsyncHandler.State} telling to CONTINUE or ABORT the current processing.
     */
    public State onContentWritten() {
        return State.CONTINUE;
    }

    /**
     * Invoked when the I/O operation associated with the {@link Request} body as been progressed.
     *
     * @param amount  The amount of bytes to transfer
     * @param current The amount of bytes transferred
     * @param total   The total number of bytes transferred
     * @return a {@link org.asynchttpclient.AsyncHandler.State} telling to CONTINUE or ABORT the current processing.
     */
    public State onContentWriteProgress(long amount, long current, long total) {
        return State.CONTINUE;
    }
}
