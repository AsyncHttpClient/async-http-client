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
 * A simple object that hold reference to an {@link AsyncHandler} and  {@link Request}
 */
public class FilterContext<T> {

    private final AsyncHandler<T> asyncHandler;
    private final Request request;
    private final HttpResponseStatus responseStatus;
    private final boolean replayRequest;

    public FilterContext(AsyncHandler<T> asyncHandler, Request request) {
        this.asyncHandler = asyncHandler;
        this.request = request;
        this.responseStatus = null;
        this.replayRequest = false;
    }

    public FilterContext(AsyncHandler<T> asyncHandler, Request request, HttpResponseStatus responseStatus) {
        this.asyncHandler = asyncHandler;
        this.request = request;
        this.responseStatus = responseStatus;
        this.replayRequest = false;
    }

    public FilterContext(AsyncHandler<T> asyncHandler, Request request, boolean replayRequest) {
        this.asyncHandler = asyncHandler;
        this.request = request;
        this.replayRequest = replayRequest;
        this.responseStatus = null;
    }

    public AsyncHandler<T> getAsyncHandler() {
        return asyncHandler;
    }

    public Request getRequest() {
        return request;
    }

    public HttpResponseStatus getResponseStatus() {
        return responseStatus;
    }

    public boolean replayRequest() {
        return replayRequest;
    }

}
