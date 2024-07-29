/*
 *    Copyright (c) 2014-2024 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.netty.request.body;

import io.netty.channel.Channel;
import io.netty.channel.ChannelProgressiveFuture;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedWriteHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.request.WriteProgressListener;
import org.asynchttpclient.request.body.Body;
import org.asynchttpclient.request.body.RandomAccessBody;
import org.asynchttpclient.request.body.generator.BodyGenerator;
import org.asynchttpclient.request.body.generator.FeedListener;
import org.asynchttpclient.request.body.generator.FeedableBodyGenerator;

import static org.asynchttpclient.util.MiscUtils.closeSilently;

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
    public void write(final Channel channel, NettyResponseFuture<?> future) {

        Object msg;
        if (body instanceof RandomAccessBody && !ChannelManager.isSslHandlerConfigured(channel.pipeline()) && !config.isDisableZeroCopy() && getContentLength() > 0) {
            msg = new BodyFileRegion((RandomAccessBody) body);

        } else {
            msg = new BodyChunkedInput(body);

            BodyGenerator bg = future.getTargetRequest().getBodyGenerator();
            if (bg instanceof FeedableBodyGenerator) {
                final ChunkedWriteHandler chunkedWriteHandler = channel.pipeline().get(ChunkedWriteHandler.class);
                ((FeedableBodyGenerator) bg).setListener(new FeedListener() {
                    @Override
                    public void onContentAdded() {
                        chunkedWriteHandler.resumeTransfer();
                    }

                    @Override
                    public void onError(Throwable t) {
                    }
                });
            }
        }

        channel.write(msg, channel.newProgressivePromise())
                .addListener(new WriteProgressListener(future, false, getContentLength()) {
                    @Override
                    public void operationComplete(ChannelProgressiveFuture cf) {
                        closeSilently(body);
                        super.operationComplete(cf);
                    }
                });
        channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT, channel.voidPromise());
    }
}
