/*
 * Copyright 2010 Ning, Inc.
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
 *
 */
package com.ning.http.client.providers.netty;

import com.ning.http.client.RandomAccessBody;
import org.jboss.netty.channel.FileRegion;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;

/**
 * Adapts a {@link RandomAccessBody} to Netty's {@link FileRegion}.
 */
class BodyFileRegion
        implements FileRegion {

    private final RandomAccessBody body;

    public BodyFileRegion(RandomAccessBody body) {
        if (body == null) {
            throw new IllegalArgumentException("no body specified");
        }
        this.body = body;
    }

    public long getPosition() {
        return 0;
    }

    public long getCount() {
        return body.getContentLength();
    }

    public long transferTo(WritableByteChannel target, long position)
            throws IOException {
        return body.transferTo(position, Long.MAX_VALUE, target);
    }

    public void releaseExternalResources() {
        try {
            body.close();
        }
        catch (IOException e) {
            // we tried
        }
    }

}
