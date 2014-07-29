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

import org.jboss.netty.channel.FileRegion;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;

public class OptimizedFileRegion implements FileRegion {

    private final FileChannel file;
    private final RandomAccessFile raf;
    private final long position;
    private final long count;
    private long byteWritten;

    public OptimizedFileRegion(RandomAccessFile raf, long position, long count) {
        this.raf = raf;
        this.file = raf.getChannel();
        this.position = position;
        this.count = count;
    }

    public long getPosition() {
        return position;
    }

    public long getCount() {
        return count;
    }

    public long transferTo(WritableByteChannel target, long position) throws IOException {
        long count = this.count - position;
        if (count < 0 || position < 0) {
            throw new IllegalArgumentException("position out of range: " + position + " (expected: 0 - " + (this.count - 1) + ")");
        }
        if (count == 0) {
            return 0L;
        }

        long bw = file.transferTo(this.position + position, count, target);
        byteWritten += bw;
        if (byteWritten == raf.length()) {
            releaseExternalResources();
        }
        return bw;
    }

    public void releaseExternalResources() {
        closeSilently(file);
        closeSilently(raf);
    }
}
