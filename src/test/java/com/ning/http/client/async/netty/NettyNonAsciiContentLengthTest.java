package com.ning.http.client.async.netty;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.async.NonAsciiContentLengthTest;
import com.ning.http.client.async.ProviderUtil;

public class NettyNonAsciiContentLengthTest extends NonAsciiContentLengthTest {

    @Override
	public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
		return ProviderUtil.nettyProvider(config);
	}
}
