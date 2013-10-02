package org.asynchttpclient.providers.netty.future;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;

import java.net.URI;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.ProxyServer;
import org.asynchttpclient.Request;
import org.asynchttpclient.util.AsyncHttpProviderUtils;

public class NettyResponseFutures {

    public static <T> NettyResponseFuture<T> newNettyResponseFuture(URI uri, Request request, AsyncHandler<T> asyncHandler, HttpRequest nettyRequest, AsyncHttpClientConfig config, ProxyServer proxyServer) {

        int requestTimeout = AsyncHttpProviderUtils.requestTimeout(config, request);
        NettyResponseFuture<T> f = new NettyResponseFuture<T>(uri,//
                request,//
                asyncHandler,//
                nettyRequest,//
                requestTimeout,//
                config,//
                request.getConnectionPoolKeyStrategy(),//
                proxyServer);

        String expectHeader = request.getHeaders().getFirstValue(HttpHeaders.Names.EXPECT);
        if (expectHeader != null && expectHeader.equalsIgnoreCase(HttpHeaders.Values.CONTINUE)) {
            f.getAndSetWriteBody(false);
        }
        return f;
    }
    
    public static boolean abortOnConnectCloseException(Throwable cause) {
        try {
            for (StackTraceElement element : cause.getStackTrace()) {
                if (element.getClassName().equals("sun.nio.ch.SocketChannelImpl") && element.getMethodName().equals("checkConnect")) {
                    return true;
                }
            }

            if (cause.getCause() != null) {
                return abortOnConnectCloseException(cause.getCause());
            }

        } catch (Throwable t) {
        }
        return false;
    }

    public static boolean abortOnDisconnectException(Throwable cause) {
        try {
            for (StackTraceElement element : cause.getStackTrace()) {
                if (element.getClassName().equals("io.netty.handler.ssl.SslHandler") && element.getMethodName().equals("channelDisconnected")) {
                    return true;
                }
            }

            if (cause.getCause() != null) {
                return abortOnConnectCloseException(cause.getCause());
            }

        } catch (Throwable t) {
        }
        return false;
    }

    public static boolean abortOnReadCloseException(Throwable cause) {

        for (StackTraceElement element : cause.getStackTrace()) {
            if (element.getClassName().equals("sun.nio.ch.SocketDispatcher") && element.getMethodName().equals("read")) {
                return true;
            }
        }

        if (cause.getCause() != null) {
            return abortOnReadCloseException(cause.getCause());
        }

        return false;
    }

    public static boolean abortOnWriteCloseException(Throwable cause) {

        for (StackTraceElement element : cause.getStackTrace()) {
            if (element.getClassName().equals("sun.nio.ch.SocketDispatcher") && element.getMethodName().equals("write")) {
                return true;
            }
        }

        if (cause.getCause() != null) {
            return abortOnReadCloseException(cause.getCause());
        }

        return false;
    }
}
