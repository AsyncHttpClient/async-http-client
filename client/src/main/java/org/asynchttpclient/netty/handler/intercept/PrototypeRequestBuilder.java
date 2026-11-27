/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.netty.handler.intercept;

import org.asynchttpclient.DefaultRequest;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilderBase;

/**
 * Copies a request as {@link Request#toBuilder()} does, but keeps its URI as it is: {@code toBuilder()} encodes the
 * URI again, which changes one built with URL encoding disabled.
 */
final class PrototypeRequestBuilder extends RequestBuilderBase<PrototypeRequestBuilder> {

    private PrototypeRequestBuilder(Request prototype) {
        super(prototype, true, false);
    }

    /**
     * A builder for a copy of {@code request} with its URI as it is. Exact class only: a subclass or another
     * {@link Request} implementation goes through its own {@link Request#toBuilder()}, as it may override what the
     * builder copies.
     */
    static RequestBuilderBase<?> copyOf(Request request) {
        return request.getClass() == DefaultRequest.class ? new PrototypeRequestBuilder(request) : request.toBuilder();
    }
}
