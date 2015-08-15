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
package com.ning.http.client.providers.netty.request.body;

import static com.ning.http.util.MiscUtils.closeSilently;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;

import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Body;
import com.ning.http.client.BodyGenerator;
import com.ning.http.client.RandomAccessBody;
import com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig;
import com.ning.http.client.providers.netty.channel.ChannelManager;
import com.ning.http.client.providers.netty.future.NettyResponseFuture;
import com.ning.http.client.providers.netty.request.body.FeedableBodyGenerator.FeedListener;
import com.ning.http.client.providers.netty.request.ProgressListener;

import java.io.IOException;

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
                final FeedableBodyGenerator feedableBodyGenerator = (FeedableBodyGenerator) bg;
                feedableBodyGenerator.writeChunkBoundaries();
                feedableBodyGenerator.setListener(new FeedListener() {
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
