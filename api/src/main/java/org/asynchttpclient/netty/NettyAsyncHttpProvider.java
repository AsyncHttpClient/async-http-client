package org.asynchttpclient.netty;

import org.asynchttpclient.AsyncHttpProvider;
import org.asynchttpclient.config.AsyncHttpClientConfig;
import org.asynchttpclient.future.ListenableFuture;
import org.asynchttpclient.handler.AsyncHandler;
import org.asynchttpclient.request.Request;

public class NettyAsyncHttpProvider implements AsyncHttpProvider {

    public NettyAsyncHttpProvider(AsyncHttpClientConfig config) {
        throw new UnsupportedOperationException("This implementation is just a stub");
    }
    
    @Override
    public <T> ListenableFuture<T> execute(Request request, AsyncHandler<T> handler) {
        throw new UnsupportedOperationException("This implementation is just a stub");
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("This implementation is just a stub");
    }
}
