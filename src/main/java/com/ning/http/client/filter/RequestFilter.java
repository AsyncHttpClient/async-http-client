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

/**
 * A Filter interface that gets invoked before making an actual request.
 */
public interface RequestFilter {

    /**
     * An {@link com.ning.http.client.AsyncHttpProvider} will invoke {@link RequestFilter#filter} and will use the
     * returned {@link FilterContext#getRequest()} and {@link FilterContext#getAsyncHandler()} to continue the request
     * processing.
     *
     * @param ctx a {@link FilterContext}
     * @return {@link FilterContext}. The {@link FilterContext} instance may not the same as the original one.
     * @throws FilterException to interrupt the filter processing.
     */
    public FilterContext filter(FilterContext ctx) throws FilterException;

}
