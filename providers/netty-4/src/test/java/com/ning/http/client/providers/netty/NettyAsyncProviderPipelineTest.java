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

package com.ning.http.client.providers.netty;

import com.ning.http.client.providers.netty_4.NettyAsyncHttpProvider;
import static org.testng.Assert.assertEquals;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.handler.codec.http.HttpMessage;
import org.testng.Assert;
import org.testng.annotations.Test;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.Response;
import org.asynchttpclient.async.AbstractBasicTest;

public class NettyAsyncProviderPipelineTest extends AbstractBasicTest {

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return new AsyncHttpClient(new CopyEncodingNettyAsyncHttpProvider(config), config);
    }

    @Test(groups = { "standalone", "netty_provider" })
    public void asyncPipelineTest() throws Throwable {
        AsyncHttpClient p = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setCompressionEnabled(true).build());
        try {
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
                Assert.fail("Timeout out");
            }
        } finally {
            p.close();
        }
    }

    private static class CopyEncodingNettyAsyncHttpProvider extends NettyAsyncHttpProvider {
        public CopyEncodingNettyAsyncHttpProvider(AsyncHttpClientConfig config) {
            super(config);
        }

        protected ChannelPipelineFactory createPlainPipelineFactory() {
            final ChannelPipelineFactory pipelineFactory = super.createPlainPipelineFactory();
            return new ChannelPipelineFactory() {
                public ChannelPipeline getPipeline() throws Exception {
                    ChannelPipeline pipeline = pipelineFactory.getPipeline();
                    pipeline.addBefore("inflater", "copyEncodingHeader", new CopyEncodingHandler());
                    return pipeline;
                }
            };
        }
    }

    private static class CopyEncodingHandler extends SimpleChannelHandler {
        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) {
            Object msg = e.getMessage();
            if (msg instanceof HttpMessage) {
                HttpMessage m = (HttpMessage) msg;
                // for test there is no Content-Encoding header so just hard coding value
                // for verification
                m.setHeader("X-Original-Content-Encoding", "<original encoding>");
            }
            ctx.sendUpstream(e);
        }
    }
}
