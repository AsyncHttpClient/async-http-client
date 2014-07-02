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
package com.ning.http.client;

import com.ning.http.client.uri.UriComponents;

/**
 * Base class for callback class used by {@link com.ning.http.client.AsyncHandler}
 */
public class HttpContent {
    protected final AsyncHttpProvider provider;
    protected final UriComponents uri;

    protected HttpContent(UriComponents uri, AsyncHttpProvider provider) {
        this.provider = provider;
        this.uri = uri;
    }

    /**
     * Return the current {@link AsyncHttpProvider}
     *
     * @return the current {@link AsyncHttpProvider}
     */
    public final AsyncHttpProvider provider() {
        return provider;
    }

    /**
     * Return the request {@link UriComponents}
     *
     * @return the request {@link UriComponents}
     */
    public final UriComponents getUri() {
        return uri;
    }
}
