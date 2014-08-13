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


import org.asynchttpclient.RandomAccessBody;

import io.netty.channel.FileRegion;
import io.netty.util.AbstractReferenceCounted;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;
/**
 * Adapts a {@link RandomAccessBody} to Netty's {@link FileRegion}.
 */
public class BodyFileRegion extends AbstractReferenceCounted implements FileRegion {

    private final RandomAccessBody body;
    private long transfered;

    public BodyFileRegion(RandomAccessBody body) {
        if (body == null)
            throw new NullPointerException("body");
        this.body = body;
    }

    @Override
    public long position() {
        return 0;
    }

    @Override
    public long count() {
        return body.getContentLength();
    }

    @Override
    public long transfered() {
        return transfered;
    }

    @Override
    public long transferTo(WritableByteChannel target, long position) throws IOException {
        long written = body.transferTo(position, target);
        if (written > 0) {
            transfered += written;
        }
        return written;
    }

    @Override
    protected void deallocate() {
        closeSilently(body);
    }
}
