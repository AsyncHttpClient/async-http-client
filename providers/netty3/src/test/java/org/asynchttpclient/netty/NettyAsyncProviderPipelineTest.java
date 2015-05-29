/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */

package org.asynchttpclient.netty;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.config.AsyncHttpClientConfig;
import org.asynchttpclient.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.netty.NettyAsyncHttpProviderConfig.AdditionalPipelineInitializer;
import org.asynchttpclient.request.Request;
import org.asynchttpclient.request.RequestBuilder;
import org.asynchttpclient.response.Response;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.HttpMessage;
import org.testng.annotations.Test;

public class NettyAsyncProviderPipelineTest extends AbstractBasicTest {

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return NettyProviderUtil.nettyProvider(config);
    }

    @Test(groups = { "standalone", "netty_provider" })
    public void asyncPipelineTest() throws Exception {

        NettyAsyncHttpProviderConfig nettyConfig = new NettyAsyncHttpProviderConfig();
        nettyConfig.setHttpAdditionalPipelineInitializer(new AdditionalPipelineInitializer() {
            public void initPipeline(ChannelPipeline pipeline) throws Exception {
                pipeline.addBefore("inflater", "copyEncodingHeader", new CopyEncodingHandler());
            }
        });

        try (AsyncHttpClient p = getAsyncHttpClient(new AsyncHttpClientConfig.Builder()
                .setAsyncHttpClientProviderConfig(nettyConfig).build())) {
            final CountDownLatch l = new CountDownLatch(1);
            Request request = new RequestBuilder("GET").setUrl(getTargetUrl()).build();
            p.executeRequest(request, new AsyncCompletionHandlerAdapter() {
                @Override
                public Response onCompleted(Response response) throws Exception {
                    try {
                        assertEquals(response.getStatusCode(), 200);
                        assertEquals(response.getHeader("X-Original-Content-Encoding"), "<original encoding>");
                    } finally {
                        l.countDown();
                    }
                    return response;
                }
            }).get();
            if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("Timeout out");
            }
        }
    }

    private static class CopyEncodingHandler extends SimpleChannelUpstreamHandler {
        @Override
        public void messageReceived(final ChannelHandlerContext ctx, MessageEvent e) throws Exception {
            
            Object message = e.getMessage();
            
            if (message instanceof HttpMessage) {
                HttpMessage m = (HttpMessage) message;
                // for test there is no Content-Encoding header so just hard
                // coding value
                // for verification
                m.headers().set("X-Original-Content-Encoding", "<original encoding>");
            }
            ctx.sendUpstream(e);
        }
    }
}
