/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.asynchttpclient.providers.netty.request.body;

import io.netty.channel.Channel;
import io.netty.channel.ChannelProgressiveFuture;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedStream;

import java.io.IOException;
import java.io.InputStream;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.request.ProgressListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyInputStreamBody implements NettyBody {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyInputStreamBody.class);

    private final InputStream inputStream;

    public NettyInputStreamBody(InputStream inputStream) {
        this.inputStream = inputStream;
    }

    public InputStream getInputStream() {
        return inputStream;
    }

    @Override
    public long getContentLength() {
        return -1L;
    }

    @Override
    public String getContentType() {
        return null;
    }

    @Override
    public void write(Channel channel, NettyResponseFuture<?> future, AsyncHttpClientConfig config) throws IOException {
        final InputStream is = inputStream;

        if (future.isStreamWasAlreadyConsumed()) {
            if (is.markSupported())
                is.reset();
            else {
                LOGGER.warn("Stream has already been consumed and cannot be reset");
                return;
            }
        } else {
            future.setStreamWasAlreadyConsumed(true);
        }

        channel.write(new ChunkedStream(is), channel.newProgressivePromise()).addListener(new ProgressListener(config, future.getAsyncHandler(), future, false, getContentLength()) {
            public void operationComplete(ChannelProgressiveFuture cf) {
                try {
                    is.close();
                } catch (IOException e) {
                    LOGGER.warn("Failed to close request body: {}", e.getMessage(), e);
                }
                super.operationComplete(cf);
            }
        });
        channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }
}
