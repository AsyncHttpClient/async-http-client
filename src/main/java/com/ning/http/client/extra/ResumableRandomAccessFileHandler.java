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
 */
package com.ning.http.client.extra;

import com.ning.http.client.resumable.ResumableListener;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

/**
 * A {@link com.ning.http.client.listener.TransferListener} which use a {@link RandomAccessFile} for storing the received bytes.
 */
public class ResumableRandomAccessFileHandler implements ResumableListener {
    private final RandomAccessFile file;
    private long byteTransferred = 0;

    public ResumableRandomAccessFileHandler(RandomAccessFile file) {
        this.file = file;
        try
        {
            this.byteTransferred = file.length();
        }
        catch ( IOException e )
        {
            throw new IllegalArgumentException( "Could not access the file: " + e.getMessage(), e );
        }
    }

    /**
     * This method uses the last valid bytes written on disk to position a {@link RandomAccessFile}, allowing
     * resumable file download.
     *
     * @param buffer a {@link ByteBuffer}
     * @throws IOException
     */
    public void onBytesReceived(ByteBuffer buffer) throws IOException {
        file.seek(byteTransferred);
        file.write(buffer.array());
        byteTransferred += buffer.capacity();
    }

    public void onAllBytesReceived() {
        if (file != null) {
            try {
                file.close();
            } catch (IOException e) {
                ;
            }
        }
    }

}
