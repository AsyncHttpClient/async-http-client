/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.util;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

public final class HttpConstants {

    private HttpConstants() {
        // Prevent outside initialization
    }

    public static final class Methods {
        public static final String CONNECT = HttpMethod.CONNECT.name();
        public static final String DELETE = HttpMethod.DELETE.name();
        public static final String GET = HttpMethod.GET.name();
        public static final String HEAD = HttpMethod.HEAD.name();
        public static final String OPTIONS = HttpMethod.OPTIONS.name();
        public static final String PATCH = HttpMethod.PATCH.name();
        public static final String POST = HttpMethod.POST.name();
        public static final String PUT = HttpMethod.PUT.name();
        public static final String TRACE = HttpMethod.TRACE.name();

        private Methods() {
            // Prevent outside initialization
        }
    }

    public static final class ResponseStatusCodes {
        public static final int CONTINUE_100 = HttpResponseStatus.CONTINUE.code();
        public static final int SWITCHING_PROTOCOLS_101 = HttpResponseStatus.SWITCHING_PROTOCOLS.code();
        public static final int OK_200 = HttpResponseStatus.OK.code();
        public static final int MOVED_PERMANENTLY_301 = HttpResponseStatus.MOVED_PERMANENTLY.code();
        public static final int FOUND_302 = HttpResponseStatus.FOUND.code();
        public static final int SEE_OTHER_303 = HttpResponseStatus.SEE_OTHER.code();
        public static final int TEMPORARY_REDIRECT_307 = HttpResponseStatus.TEMPORARY_REDIRECT.code();
        public static final int PERMANENT_REDIRECT_308 = HttpResponseStatus.PERMANENT_REDIRECT.code();
        public static final int UNAUTHORIZED_401 = HttpResponseStatus.UNAUTHORIZED.code();
        public static final int PROXY_AUTHENTICATION_REQUIRED_407 = HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED.code();

        private ResponseStatusCodes() {
            // Prevent outside initialization
        }
    }
}
