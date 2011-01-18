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
package com.ning.http.client.consumers;

import com.ning.http.client.ResumableBodyConsumer;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

public class FileBodyConsumer implements ResumableBodyConsumer {

    private final RandomAccessFile file;

    public FileBodyConsumer(RandomAccessFile file) {
        this.file = file;
    }

    public void consume(ByteBuffer byteBuffer) throws IOException {
        // TODO: Channel.transferFrom may be a good idea to investigate.
        file.write(byteBuffer.array());
    }

    public void close() throws IOException {
        file.close();
    }

    public long getTransferredBytes() throws IOException {
        return file.length();
    }
    
    public void resume() throws IOException
    {
        file.seek( getTransferredBytes() );
    }
}
