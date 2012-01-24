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
package com.ning.http.client.extra;

import com.ning.http.client.resumable.ResumableListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

/**
 * A {@link com.ning.http.client.listener.TransferListener} which use a {@link RandomAccessFile} for storing the received bytes.
 */
public class ResumableRandomAccessFileListener implements ResumableListener {
    private final RandomAccessFile file;
    private final static Logger logger = LoggerFactory.getLogger(ThrottleRequestFilter.class);

    public ResumableRandomAccessFileListener(RandomAccessFile file) {
        this.file = file;
    }

    /**
     * This method uses the last valid bytes written on disk to position a {@link RandomAccessFile}, allowing
     * resumable file download.
     *
     * @param buffer a {@link ByteBuffer}
     * @throws IOException
     */
    public void onBytesReceived(ByteBuffer buffer) throws IOException {
        file.seek(file.length());
        file.write(buffer.array());
    }

    /**
     * {@inheritDoc}
     */
    public void onAllBytesReceived() {
        if (file != null) {
            try {
                file.close();
            } catch (IOException e) {
                ;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public long length() {
        try {
            return file.length();
        } catch (IOException e) {
            ;
        }
        return 0;
    }

}
