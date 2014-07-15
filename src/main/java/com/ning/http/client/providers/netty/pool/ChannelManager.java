package com.ning.http.client.providers.netty.pool;

import org.jboss.netty.channel.Channel;

public class ChannelManager {

    private final ChannelPool channelPool;

    public ChannelManager(ChannelPool channelPool) {
        this.channelPool = channelPool;
    }

    public boolean offer(String uri, Channel connection) {
        return channelPool.offer(uri, connection);
    }

    public Channel poll(String uri) {
        return channelPool.poll(uri);
    }

    public boolean removeAll(Channel connection) {
        return channelPool.removeAll(connection);
    }

    public boolean canCacheConnection() {
        return channelPool.canCacheConnection();
    }

    public void destroy() {
        channelPool.destroy();
        ;
    }
}
