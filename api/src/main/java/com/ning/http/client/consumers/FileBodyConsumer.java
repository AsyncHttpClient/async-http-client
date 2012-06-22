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
package com.ning.http.client.consumers;

import com.ning.http.client.ResumableBodyConsumer;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

/**
 * A {@link RandomAccessFile} that can be used as a {@link ResumableBodyConsumer}
 */
public class FileBodyConsumer implements ResumableBodyConsumer {

    private final RandomAccessFile file;

    public FileBodyConsumer(RandomAccessFile file) {
        this.file = file;
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public void consume(ByteBuffer byteBuffer) throws IOException {
        // TODO: Channel.transferFrom may be a good idea to investigate.
        file.write(byteBuffer.array());
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public void close() throws IOException {
        file.close();
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public long getTransferredBytes() throws IOException {
        return file.length();
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public void resume() throws IOException {
        file.seek(getTransferredBytes());
    }
}
