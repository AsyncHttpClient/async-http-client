package org.asynchttpclient.netty;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpProvider;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.Request;

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
