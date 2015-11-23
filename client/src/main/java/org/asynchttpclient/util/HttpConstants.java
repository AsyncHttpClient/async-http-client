package org.asynchttpclient.util;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

public final class HttpConstants {

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
        }
    }

    public static final class ResponseStatusCodes {
        public static final int CONTINUE_100 = HttpResponseStatus.CONTINUE.code();
        public static final int SWITCHING_PROTOCOLS_101 = HttpResponseStatus.SWITCHING_PROTOCOLS.code();
        public static final int OK_200 = HttpResponseStatus.OK.code();
        public static final int MOVED_PERMANENTLY_301 = HttpResponseStatus.MOVED_PERMANENTLY.code();
        public static final int FOUND_302 = HttpResponseStatus.FOUND.code();
        public static final int SEE_OTHER_303 = HttpResponseStatus.SEE_OTHER.code();
        public static final int NOT_MODIFIED_304 = HttpResponseStatus.NOT_MODIFIED.code();
        public static final int TEMPORARY_REDIRECT_307 = HttpResponseStatus.TEMPORARY_REDIRECT.code();
        public static final int UNAUTHORIZED_401 = HttpResponseStatus.UNAUTHORIZED.code();
        public static final int PROXY_AUTHENTICATION_REQUIRED_407 = HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED.code();

        private ResponseStatusCodes() {
        }
    }

    private HttpConstants() {
    }
}
