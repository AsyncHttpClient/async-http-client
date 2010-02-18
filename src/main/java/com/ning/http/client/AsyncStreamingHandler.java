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

import com.ning.http.client.Response;

import java.io.IOException;

/**
 * An asynchronous {@link AsyncHandler} which gets invoked as soon as some data are available when
 * processing an asynchronous response.
 */
public abstract class AsyncStreamingHandler implements AsyncHandler<Response> {

    public static class ResponseComplete extends RuntimeException {

        private static final long serialVersionUID = 11660101L;

        public ResponseComplete() {
            super("Response completed.");
        }
    }

    /*package */public Response onCompleted(Response response) throws IOException {
        return response;
    }

    /**
     * Invoked as soon as some response's headers or body are available for processing.
     *
     * @param content an instance of {@link HttpContent}, which can be an instance of
     *                {@link HttpResponseHeaders}
     *                or {@link HttpResponseBody}
     */
    abstract public Response onContentReceived(HttpContent content);

}
