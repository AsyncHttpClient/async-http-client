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
package org.asynchttpclient.netty.request.body;

import static org.asynchttpclient.util.MiscUtils.closeSilently;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelProgressiveFuture;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedWriteHandler;

import java.io.IOException;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.request.ProgressListener;
import org.asynchttpclient.netty.request.body.FeedableBodyGenerator.FeedListener;
import org.asynchttpclient.request.body.Body;
import org.asynchttpclient.request.body.RandomAccessBody;
import org.asynchttpclient.request.body.generator.BodyGenerator;

public class NettyBodyBody implements NettyBody {

    private final Body body;
    private final AsyncHttpClientConfig config;

    public NettyBodyBody(Body body, AsyncHttpClientConfig config) {
        this.body = body;
        this.config = config;
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
    };

    @Override
    public void write(final Channel channel, NettyResponseFuture<?> future) throws IOException {

        Object msg;
        if (body instanceof RandomAccessBody && !ChannelManager.isSslHandlerConfigured(channel.pipeline()) && !config.isDisableZeroCopy()) {
            msg = new BodyFileRegion((RandomAccessBody) body);

        } else {
            msg = new BodyChunkedInput(body);

            BodyGenerator bg = future.getRequest().getBodyGenerator();
            if (bg instanceof FeedableBodyGenerator) {
                FeedableBodyGenerator.class.cast(bg).setListener(new FeedListener() {
                    @Override
                    public void onContentAdded() {
                        channel.pipeline().get(ChunkedWriteHandler.class).resumeTransfer();
                    }
                });
            }
        }
        ChannelFuture writeFuture = channel.write(msg, channel.newProgressivePromise());

        writeFuture.addListener(new ProgressListener(config, future.getAsyncHandler(), future, false, getContentLength()) {
            public void operationComplete(ChannelProgressiveFuture cf) {
                closeSilently(body);
                super.operationComplete(cf);
            }
        });
        channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }
}
