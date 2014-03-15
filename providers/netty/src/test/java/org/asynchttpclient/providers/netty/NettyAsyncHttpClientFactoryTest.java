package org.asynchttpclient.providers.netty;

import org.asynchttpclient.AbstractAsyncHttpClientFactoryTest;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpProvider;
import org.testng.annotations.Test;

@Test
public class NettyAsyncHttpClientFactoryTest extends AbstractAsyncHttpClientFactoryTest {

    @Override
    public AsyncHttpProvider getAsyncHttpProvider(AsyncHttpClientConfig config) {
        if (config == null) {
            config = new AsyncHttpClientConfig.Builder().build();
        }
        return new NettyAsyncHttpProvider(config);
    }

}
