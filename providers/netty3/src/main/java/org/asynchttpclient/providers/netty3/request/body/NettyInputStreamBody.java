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
package org.asynchttpclient.providers.netty3.request.body;

import static org.asynchttpclient.util.MiscUtils.closeSilently;

import java.io.IOException;
import java.io.InputStream;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Body;
import org.asynchttpclient.generators.InputStreamBodyGenerator;
import org.asynchttpclient.providers.netty3.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty3.request.ProgressListener;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
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

        InputStreamBodyGenerator generator = new InputStreamBodyGenerator(is);
        // FIXME is this still usefull?
        generator.patchNetty3ChunkingIssue(true);
        final Body body = generator.createBody();
        channel.write(new BodyChunkedInput(body)).addListener(new ProgressListener(config, future.getAsyncHandler(), future, false) {
            public void operationComplete(ChannelFuture cf) {
                closeSilently(body);
                super.operationComplete(cf);
            }
        });
        
        // FIXME ChunkedStream is broken in Netty 3 but fixed in Netty 4
//        channel.write(new ChunkedStream(is)).addListener(
//                new ProgressListener(config, future.getAsyncHandler(), future, false) {
//                    public void operationComplete(ChannelFuture cf) {
//                        try {
//                            is.close();
//                        } catch (IOException e) {
//                            LOGGER.warn("Failed to close request body: {}", e.getMessage(), e);
//                        }
//                        super.operationComplete(cf);
//                    }
//                });
    }
}
