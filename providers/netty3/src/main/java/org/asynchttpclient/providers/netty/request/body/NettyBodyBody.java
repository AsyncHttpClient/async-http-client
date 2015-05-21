/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.providers.netty.request.body;

import static org.asynchttpclient.util.MiscUtils.closeSilently;

import java.io.IOException;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Body;
import org.asynchttpclient.BodyGenerator;
import org.asynchttpclient.RandomAccessBody;
import org.asynchttpclient.providers.netty.NettyAsyncHttpProviderConfig;
import org.asynchttpclient.providers.netty.channel.ChannelManager;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.request.ProgressListener;
import org.asynchttpclient.providers.netty.request.body.FeedableBodyGenerator.FeedListener;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;

public class NettyBodyBody implements NettyBody {

    private final Body body;
    private final NettyAsyncHttpProviderConfig nettyConfig;

    public NettyBodyBody(Body body, NettyAsyncHttpProviderConfig nettyConfig) {
        this.body = body;
        this.nettyConfig = nettyConfig;
    }

    public Body getBody() {
        return body;
    }

    @Override
    public long getContentLength() {
        return body.getContentLength();
    }

    @Override
    public String getContentType() {
        return null;
    }

    @Override
    public void write(final Channel channel, NettyResponseFuture<?> future, AsyncHttpClientConfig config) throws IOException {

        Object msg;
        if (body instanceof RandomAccessBody && !ChannelManager.isSslHandlerConfigured(channel.getPipeline()) && !nettyConfig.isDisableZeroCopy()) {
            msg = new BodyFileRegion((RandomAccessBody) body);

        } else {
            msg = new BodyChunkedInput(body);
            
            BodyGenerator bg = future.getRequest().getBodyGenerator();
            if (bg instanceof FeedableBodyGenerator) {
                FeedableBodyGenerator.class.cast(bg).setListener(new FeedListener() {
                    @Override
                    public void onContentAdded() {
                        channel.getPipeline().get(ChunkedWriteHandler.class).resumeTransfer();
                    }
                });
            }
        }
        
        channel.write(msg).addListener(new ProgressListener(config, future.getAsyncHandler(), future, false) {
            public void operationComplete(ChannelFuture cf) {
                closeSilently(body);
                super.operationComplete(cf);
            }
        });
    }
}
