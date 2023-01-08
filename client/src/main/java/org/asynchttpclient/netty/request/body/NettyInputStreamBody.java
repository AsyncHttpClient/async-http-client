/*
 *    Copyright (c) 2014-2023 AsyncHttpClient Project. All rights reserved.
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
import io.netty.handler.stream.ChunkedStream;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.request.WriteProgressListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

import static org.asynchttpclient.util.MiscUtils.closeSilently;

public class NettyInputStreamBody implements NettyBody {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyInputStreamBody.class);

    private final InputStream inputStream;
    private final long contentLength;

    public NettyInputStreamBody(InputStream inputStream) {
        this(inputStream, -1L);
    }

    public NettyInputStreamBody(InputStream inputStream, long contentLength) {
        this.inputStream = inputStream;
        this.contentLength = contentLength;
    }

    public InputStream getInputStream() {
        return inputStream;
    }

    @Override
    public long getContentLength() {
        return contentLength;
    }

    @Override
    public void write(Channel channel, NettyResponseFuture<?> future) throws IOException {
        final InputStream is = inputStream;

        if (future.isStreamConsumed()) {
            if (is.markSupported()) {
                is.reset();
            } else {
                LOGGER.warn("Stream has already been consumed and cannot be reset");
                return;
            }
        } else {
            future.setStreamConsumed(true);
        }

        channel.write(new ChunkedStream(is), channel.newProgressivePromise()).addListener(
                new WriteProgressListener(future, false, getContentLength()) {
                    @Override
                    public void operationComplete(ChannelProgressiveFuture cf) {
                        closeSilently(is);
                        super.operationComplete(cf);
                    }
                });
        channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT, channel.voidPromise());
    }
}
