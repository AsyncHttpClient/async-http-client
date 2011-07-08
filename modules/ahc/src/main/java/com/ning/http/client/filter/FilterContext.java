/*
 * Copyright (c) 2010-2011 Sonatype, Inc. All rights reserved.
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
package com.ning.http.client.filter;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Request;

import java.io.IOException;

/**
 * A {@link FilterContext} can be used to decorate {@link Request} and {@link AsyncHandler} from a list of {@link RequestFilter}.
 * {@link RequestFilter} gets executed before the HTTP request is made to the remote server. Once the response bytes are
 * received, a {@link FilterContext} is then passed to the list of {@link ResponseFilter}. {@link ResponseFilter}
 * gets invoked before the response gets processed, e.g. before authorization, redirection and invokation of {@link AsyncHandler}
 * gets processed.
 * <p/>
 * Invoking {@link com.ning.http.client.filter.FilterContext#getResponseStatus()} returns an instance of {@link HttpResponseStatus}
 * that can be used to decide if the response processing should continue or not. You can stop the current response processing
 * and replay the request but creating a {@link FilterContext}. The {@link com.ning.http.client.AsyncHttpProvider}
 * will interrupt the processing and "replay" the associated {@link Request} instance.
 */
public class FilterContext<T> {

    private final AsyncHandler<T> asyncHandler;
    private final Request request;
    private final HttpResponseStatus responseStatus;
    private final boolean replayRequest;
    private final IOException ioException;

    /**
     * Create a new {@link FilterContext}
     *
     * @param asyncHandler an {@link AsyncHandler}
     * @param request      a {@link Request}
     * @deprecated use {@link FilterContextBuilder} instead
     */
    public FilterContext(AsyncHandler<T> asyncHandler, Request request) {
        this(asyncHandler, request, null, false, null);
    }

    /**
     * Create a new {@link FilterContext}
     *
     * @param asyncHandler an {@link AsyncHandler}
     * @param request      a {@link Request}
     * @param ioException  an {@link IOException}
     * @deprecated use {@link FilterContextBuilder} instead
     */
    public FilterContext(AsyncHandler<T> asyncHandler, Request request, IOException ioException) {
        this(asyncHandler, request, null, false, ioException);
    }

    /**
     * Create a new {@link FilterContext}
     *
     * @param asyncHandler   an {@link AsyncHandler}
     * @param request        a {@link Request}
     * @param responseStatus a {@link HttpResponseStatus}
     * @deprecated use {@link FilterContextBuilder} instead
     */
    public FilterContext(AsyncHandler<T> asyncHandler, Request request, HttpResponseStatus responseStatus) {
        this(asyncHandler, request, responseStatus, false, null);

    }

    private FilterContext(AsyncHandler<T> asyncHandler, Request request, HttpResponseStatus responseStatus,
                          boolean replayRequest, IOException ioException) {
        this.asyncHandler = asyncHandler;
        this.request = request;
        this.responseStatus = responseStatus;
        this.replayRequest = replayRequest;
        this.ioException = ioException;
    }

    /**
     * Create a new {@link FilterContext}
     *
     * @param asyncHandler  an {@link AsyncHandler}
     * @param request       a {@link Request}
     * @param replayRequest true if the current response processing needs to be interrupted, and a new {@link Request} be processed.
     * @deprecated use {@link FilterContextBuilder} instead
     */
    public FilterContext(AsyncHandler<T> asyncHandler, Request request, boolean replayRequest) {
        this(asyncHandler, request, null, replayRequest, null);
    }

    /**
     * Return the original or decorated {@link AsyncHandler}
     *
     * @return the original or decorated {@link AsyncHandler}
     */
    public AsyncHandler<T> getAsyncHandler() {
        return asyncHandler;
    }

    /**
     * Return the original or decorated {@link Request}
     *
     * @return the original or decorated {@link Request}
     */
    public Request getRequest() {
        return request;
    }

    /**
     * Return the unprocessed response's {@link HttpResponseStatus}
     *
     * @return the unprocessed response's {@link HttpResponseStatus}
     */
    public HttpResponseStatus getResponseStatus() {
        return responseStatus;
    }

    /**
     * Return true if the current response's processing needs to be interrupted and a new {@link Request} be executed.
     *
     * @return true if the current response's processing needs to be interrupted and a new {@link Request} be executed.
     */
    public boolean replayRequest() {
        return replayRequest;
    }

    /**
     * Return the {@link IOException}
     *
     * @return the {@link IOException}
     */
    public IOException getIOException() {
        return ioException;
    }

    public static class FilterContextBuilder<T> {
        private AsyncHandler<T> asyncHandler = null;
        private Request request = null;
        private HttpResponseStatus responseStatus = null;
        private boolean replayRequest = false;
        private IOException ioException = null;

        public FilterContextBuilder() {
        }

        public FilterContextBuilder(FilterContext clone) {
            asyncHandler = clone.getAsyncHandler();
            request = clone.getRequest();
            responseStatus = clone.getResponseStatus();
            replayRequest = clone.replayRequest();
            ioException = clone.getIOException();
        }

        public AsyncHandler<T> getAsyncHandler() {
            return asyncHandler;
        }

        public FilterContextBuilder asyncHandler(AsyncHandler<T> asyncHandler) {
            this.asyncHandler = asyncHandler;
            return this;
        }

        public Request getRequest() {
            return request;
        }

        public FilterContextBuilder request(Request request) {
            this.request = request;
            return this;
        }

        public HttpResponseStatus getResponseStatus() {
            return responseStatus;
        }

        public FilterContextBuilder responseStatus(HttpResponseStatus responseStatus) {
            this.responseStatus = responseStatus;
            return this;
        }

        public boolean replayRequest() {
            return replayRequest;
        }

        public FilterContextBuilder replayRequest(boolean replayRequest) {
            this.replayRequest = replayRequest;
            return this;
        }

        public IOException getIoException() {
            return ioException;
        }

        public FilterContextBuilder ioException(IOException ioException) {
            this.ioException = ioException;
            return this;
        }

        public FilterContext build() {
            return new FilterContext(asyncHandler, request, responseStatus, replayRequest, ioException);
        }
    }

}
