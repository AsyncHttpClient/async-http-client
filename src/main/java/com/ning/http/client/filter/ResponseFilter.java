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
package com.ning.http.client.filter;

/**
 * A Filter interface that gets invoked before making the processing of the response bytes. {@link ResponseFilter} are invoked
 * before the actual response's status code get processed. That means authorization, proxy authentication and redirects
 * processing hasn't occured when {@link ResponseFilter} gets invoked.
 */
public interface ResponseFilter {

    /**
     * An {@link com.ning.http.client.AsyncHttpProvider} will invoke {@link ResponseFilter#filter} and will use the
     * returned {@link FilterContext#replayRequest()} and {@link FilterContext#getAsyncHandler()} to decide if the response
     * processing can continue. If {@link FilterContext#replayRequest()} return true, a new request will be made
     * using {@link FilterContext#getRequest()} and the current response processing will be ignored.
     *
     * @param ctx a {@link FilterContext}
     * @return {@link FilterContext}. The {@link FilterContext} instance may not the same as the original one.
     * @throws FilterException to interrupt the filter processing.
     */
    public FilterContext filter(FilterContext ctx) throws FilterException;

}
