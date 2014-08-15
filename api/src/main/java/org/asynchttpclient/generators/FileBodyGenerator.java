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
package org.asynchttpclient.generators;

import org.asynchttpclient.BodyGenerator;
import org.asynchttpclient.RandomAccessBody;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;

/**
 * Creates a request body from the contents of a file.
 */
//Not used by Netty
public class FileBodyGenerator implements BodyGenerator {

    private final File file;
    private final long regionSeek;
    private final long regionLength;

    public FileBodyGenerator(File file) {
        this(file, 0L, file.length());
    }

    public FileBodyGenerator(File file, long regionSeek, long regionLength) {
        if (file == null) {
            throw new NullPointerException("file");
        }
        this.file = file;
        this.regionLength = regionLength;
        this.regionSeek = regionSeek;
    }

    public File getFile() {
        return file;
    }

    public long getRegionLength() {
        return regionLength;
    }

    public long getRegionSeek() {
        return regionSeek;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RandomAccessBody createBody() throws IOException {
        return new FileBody(file, regionSeek, regionLength);
    }

    private static class FileBody implements RandomAccessBody {

        private final RandomAccessFile raf;

        private final FileChannel channel;

        private final long length;

        private FileBody(File file, long regionSeek, long regionLength) throws IOException {
            raf = new RandomAccessFile(file, "r");
            channel = raf.getChannel();
            length = regionLength;
            if (regionSeek > 0) {
                raf.seek(regionSeek);
            }
        }

        public long getContentLength() {
            return length;
        }

        public long read(ByteBuffer buffer) throws IOException {
            return channel.read(buffer);
        }

        public long transferTo(long position, WritableByteChannel target) throws IOException {
            return channel.transferTo(position, length, target);
        }

        public void close() throws IOException {
            raf.close();
        }
    }
}
