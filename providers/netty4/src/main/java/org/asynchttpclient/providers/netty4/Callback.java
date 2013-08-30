package org.asynchttpclient.providers.netty4;

public abstract class Callback {

    private final NettyResponseFuture<?> future;

    public Callback(NettyResponseFuture<?> future) {
        this.future = future;
    }

    abstract public void call() throws Exception;

    public NettyResponseFuture<?> future() {
        return future;
    }
}