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
package com.ning.http.client;

/**
 * An {@link AsyncHandler} augmented with an {@link #onCompleted(Response)} convenience method which gets called
 * when the {@link Response} has been fully received.
 * 
 * @param <T>
 */
public abstract class AsyncCompletionHandler<T> implements AsyncHandler<T>{
    /**
     * {@inheritDoc}
     */
    @Override
    public void onBodyPartReceived(final HttpResponseBodyPart content) throws Exception {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onStatusReceived(final HttpResponseStatus responseStatus) throws Exception {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onHeadersReceived(final HttpResponseHeaders headers) throws Exception {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T onCompleted() throws Exception {
        return null;
    }

    /**
     * Invoked once the HTTP response has been fully read.
     *
     * @param response The {@link Response}
     */
    abstract public T onCompleted(Response response) throws Exception;
}
