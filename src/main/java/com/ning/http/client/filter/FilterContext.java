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
package com.ning.http.client.filter;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Request;

/**
 * A {@link FilterContext} can be used to decorate {@link Request} and {@link AsyncHandler} from a list of {@link RequestFilter}.
 * {@link RequestFilter} gets executed before the HTTP request is made to the remote server. Once the response bytes are
 * received, a {@link FilterContext} is then passed to the list of {@link ResponseFilter}. {@link ResponseFilter}
 * gets invoked before the response gets processed, e.g. before authorization, redirection and invokation of {@link AsyncHandler}
 * gets processed.
 * <p/>
 * Invoking {@link com.ning.http.client.filter.FilterContext#getResponseStatus()} returns an instance of {@link HttpResponseStatus}
 * that can be used to decide if the response processing should continue or not. You can stop the current response processing
 * and replay the request but creating a {@link FilterContext(AsyncHandler<T>, Request, boolean)}. The {@link com.ning.http.client.AsyncHttpProvider}
 * will interrupt the processing and "replay" the associated {@link Request} instance.
 */
public class FilterContext<T> {

    private final AsyncHandler<T> asyncHandler;
    private final Request request;
    private final HttpResponseStatus responseStatus;
    private final boolean replayRequest;

    /**
     * Create a new {@link FilterContext}
     *
     * @param asyncHandler an {@link AsyncHandler}
     * @param request      a {@link Request}
     */
    public FilterContext(AsyncHandler<T> asyncHandler, Request request) {
        this.asyncHandler = asyncHandler;
        this.request = request;
        this.responseStatus = null;
        this.replayRequest = false;
    }

    /**
     * Create a new {@link FilterContext}
     *
     * @param asyncHandler   an {@link AsyncHandler}
     * @param request        a {@link Request}
     * @param responseStatus a {@link HttpResponseStatus}
     */
    public FilterContext(AsyncHandler<T> asyncHandler, Request request, HttpResponseStatus responseStatus) {
        this.asyncHandler = asyncHandler;
        this.request = request;
        this.responseStatus = responseStatus;
        this.replayRequest = false;
    }

    /**
     * Create a new {@link FilterContext}
     *
     * @param asyncHandler  an {@link AsyncHandler}
     * @param request       a {@link Request}
     * @param replayRequest true if the current response processing needs to be interrupted, and a new {@link Request} be processed.
     */
    public FilterContext(AsyncHandler<T> asyncHandler, Request request, boolean replayRequest) {
        this.asyncHandler = asyncHandler;
        this.request = request;
        this.replayRequest = replayRequest;
        this.responseStatus = null;
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
     * @return the unprocessed response's {@link HttpResponseStatus}
     */
    public HttpResponseStatus getResponseStatus() {
        return responseStatus;
    }

    /**
     * Return true if the current response's processing needs to be interrupted and a new {@link Request} be executed.
     * @return true if the current response's processing needs to be interrupted and a new {@link Request} be executed.
     */
    public boolean replayRequest() {
        return replayRequest;
    }

}
