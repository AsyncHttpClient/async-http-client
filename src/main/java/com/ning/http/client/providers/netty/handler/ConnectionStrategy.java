package com.ning.http.client.providers.netty.handler;

import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;

/**
 * Provides an interface for decisions about HTTP connections.
 */
public interface ConnectionStrategy {

    /**
     * Determines whether the connection should be kept alive after this HTTP message exchange.
     * @param request the HTTP request
     * @param response the HTTP response
     * @return true if the connection should be kept alive, false if it should be closed.
     */
    boolean keepAlive(HttpRequest request, HttpResponse response);
}
