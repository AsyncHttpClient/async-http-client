package org.asynchttpclient.netty.request.body;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.config.AsyncHttpClientConfig;
import org.asynchttpclient.netty.NettyProviderUtil;
import org.asynchttpclient.request.body.ChunkingTest;

public class NettyChunkingTest extends ChunkingTest {
    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return NettyProviderUtil.nettyProvider(config);
    }
}
