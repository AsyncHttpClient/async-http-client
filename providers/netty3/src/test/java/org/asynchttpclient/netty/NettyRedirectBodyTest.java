package org.asynchttpclient.netty;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.RedirectBodyTest;
import org.testng.annotations.Test;

@Test
public class NettyRedirectBodyTest extends RedirectBodyTest {

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return NettyProviderUtil.nettyProvider(config);
    }
}
