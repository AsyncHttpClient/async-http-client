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

import com.ning.http.client.providers.NettyAsyncResponse;
import com.ning.http.client.Response;

/**
 * A simple class which encapsulate a partial or complete {@link Response}. Since the {@link Response} is populated
 * asynchronously, invoking {@link com.ning.http.client.Response#getResponseBodyAsStream()} may not return the full
 * content.
 */
public class HttpContent {
    private final NettyAsyncResponse<?> response;

    protected HttpContent(NettyAsyncResponse<?> response) {
        this.response = response;
    }

    /**
     * Return a {@link Response}. The {@link Response} is populated asynchronously and may not contains the full response.
     *
     * @return
     */
    public Response getResponse() {
        return response;
    }

}
