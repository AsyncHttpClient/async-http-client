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
package org.asynchttpclient;

import org.asynchttpclient.util.UriEncoder;

/**
 * Builder for a {@link Request}.
 * Warning: mutable and not thread-safe! Beware that it holds a reference on the Request instance it builds,
 * so modifying the builder will modify the request even after it has been built.
 */
public class RequestBuilder extends RequestBuilderBase<RequestBuilder> {

    public RequestBuilder() {
        super(RequestBuilder.class, "GET", false);
    }

    public RequestBuilder(String method) {
        super(RequestBuilder.class, method, false);
    }

    public RequestBuilder(String method, boolean disableUrlEncoding) {
        super(RequestBuilder.class, method, disableUrlEncoding);
    }

    public RequestBuilder(String method, UriEncoder uriEncoder) {
        super(RequestBuilder.class, method, uriEncoder);
    }

    public RequestBuilder(Request prototype) {
        super(RequestBuilder.class, prototype);
    }

    public RequestBuilder(Request prototype, UriEncoder uriEncoder) {
        super(RequestBuilder.class, prototype, uriEncoder);
    }
}
