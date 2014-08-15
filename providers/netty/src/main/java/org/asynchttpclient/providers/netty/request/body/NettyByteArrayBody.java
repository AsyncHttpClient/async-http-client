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

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;

import io.netty.channel.Channel;

import java.io.IOException;

public class NettyByteArrayBody implements NettyBody {

    private final byte[] bytes;
    private final String contentType;

    public NettyByteArrayBody(byte[] bytes) {
        this(bytes, null);
    }

    public NettyByteArrayBody(byte[] bytes, String contentType) {
        this.bytes = bytes;
        this.contentType = contentType;
    }

    public byte[] getBytes() {
        return bytes;
    }

    @Override
    public long getContentLength() {
        return bytes.length;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public void write(Channel channel, NettyResponseFuture<?> future, AsyncHttpClientConfig config) throws IOException {
        throw new UnsupportedOperationException("This kind of body is supposed to be writen directly");
    }
}
