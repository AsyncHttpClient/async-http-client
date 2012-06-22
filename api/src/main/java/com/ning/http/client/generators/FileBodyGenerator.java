/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.ning.http.client.generators;

import com.ning.http.client.BodyGenerator;
import com.ning.http.client.RandomAccessBody;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;

/**
 * Creates a request body from the contents of a file.
 */
public class FileBodyGenerator
        implements BodyGenerator {

    private final File file;
    private final long regionSeek;
    private final long regionLength;

    public FileBodyGenerator(File file) {
        if (file == null) {
            throw new IllegalArgumentException("no file specified");
        }
        this.file = file;
        this.regionLength = file.length();
        this.regionSeek = 0;
    }

    public FileBodyGenerator(File file, long regionSeek, long regionLength) {
        if (file == null) {
            throw new IllegalArgumentException("no file specified");
        }
        this.file = file;
        this.regionLength = regionLength;
        this.regionSeek = regionSeek;
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public RandomAccessBody createBody()
            throws IOException {
        return new FileBody(file, regionSeek, regionLength);
    }

    protected static class FileBody
            implements RandomAccessBody {

        private final RandomAccessFile file;

        private final FileChannel channel;

        private final long length;

        public FileBody(File file)
                throws IOException {
            this.file = new RandomAccessFile(file, "r");
            channel = this.file.getChannel();
            length = file.length();
        }

        public FileBody(File file, long regionSeek, long regionLength)
                throws IOException {
            this.file = new RandomAccessFile(file, "r");
            channel = this.file.getChannel();
            length = regionLength;
            if (regionSeek > 0) {
                this.file.seek(regionSeek);
            }
        }

        public long getContentLength() {
            return length;
        }

        public long read(ByteBuffer buffer)
                throws IOException {
            return channel.read(buffer);
        }

        public long transferTo(long position, long count, WritableByteChannel target)
                throws IOException {
            if (count > length) {
                count = length;
            }
            return channel.transferTo(position, count, target);
        }

        public void close()
                throws IOException {
            file.close();
        }

    }

}
