package com.ning.http.client.providers.netty.handler;

import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMessage;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;

public class Http1Point1ConnectionStrategy implements ConnectionStrategy {

    @Override
    public boolean keepAlive(HttpRequest httpRequest, HttpResponse response) {
        return isConnectionKeepAlive(httpRequest) && isConnectionKeepAlive(response);
    }

    public boolean isConnectionKeepAlive(HttpMessage message) {
        return !HttpHeaders.Values.CLOSE.equalsIgnoreCase(message.headers().get(HttpHeaders.Names.CONNECTION));
    }
}
