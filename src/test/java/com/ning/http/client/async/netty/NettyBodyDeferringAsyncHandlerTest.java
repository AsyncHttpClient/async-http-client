package com.ning.http.client.async.netty;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.async.BodyDeferringAsyncHandlerTest;
import com.ning.http.client.async.ProviderUtil;

public class NettyBodyDeferringAsyncHandlerTest extends
	BodyDeferringAsyncHandlerTest {

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
	return ProviderUtil.nettyProvider(config);
    }

}
