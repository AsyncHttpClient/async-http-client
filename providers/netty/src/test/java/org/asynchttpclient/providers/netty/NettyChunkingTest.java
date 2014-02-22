package org.asynchttpclient.providers.netty;

import org.asynchttpclient.AsyncHttpClientImpl;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.async.ChunkingTest;

public class NettyChunkingTest extends ChunkingTest {
    @Override
    public AsyncHttpClientImpl getAsyncHttpClient(AsyncHttpClientConfig config) {
        return NettyProviderUtil.nettyProvider(config);
    }
}
