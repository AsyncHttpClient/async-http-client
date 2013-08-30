package org.asynchttpclient.providers.netty4;

import io.netty.channel.Channel;

public interface AdditionalChannelInitializer {

    void initChannel(Channel ch) throws Exception;
}
