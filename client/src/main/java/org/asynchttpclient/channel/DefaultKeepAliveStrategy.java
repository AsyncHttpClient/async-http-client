package org.asynchttpclient.channel;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import org.asynchttpclient.Request;

/**
 * Connection strategy implementing standard HTTP 1.0/1.1 behavior.
 */
public class DefaultKeepAliveStrategy implements KeepAliveStrategy {

    /**
     * Implemented in accordance with RFC 7230 section 6.1 https://tools.ietf.org/html/rfc7230#section-6.1
     */
    @Override
    public boolean keepAlive(Request ahcRequest, HttpRequest request, HttpResponse response) {
        return HttpHeaders.isKeepAlive(response)//
                && HttpHeaders.isKeepAlive(request)
                // support non standard Proxy-Connection
                && !response.headers().contains("Proxy-Connection", HttpHeaders.Values.CLOSE, true);
    }
}
