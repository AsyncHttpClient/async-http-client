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
package org.asynchttpclient.multipart;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.WritableByteChannel;

public class ByteArrayPart extends AbstractFilePart {

    private final byte[] bytes;
    private final String fileName;

    public ByteArrayPart(String name, byte[] bytes) {
        this(name, bytes, null);
    }

    public ByteArrayPart(String name, byte[] bytes, String contentType) {
        this(name, bytes, contentType, null);
    }

    public ByteArrayPart(String name, byte[] bytes, String contentType, String charset) {
        this(name, bytes, contentType, charset, null);
    }

    public ByteArrayPart(String name, byte[] bytes, String contentType, String charset, String fileName) {
        this(name, bytes, contentType, charset, fileName, null);
    }

    public ByteArrayPart(String name, byte[] bytes, String contentType, String charset, String fileName, String contentId) {
        super(name, contentType, charset, contentId);
        if (bytes == null) {
            throw new NullPointerException("bytes");
        }
        this.bytes = bytes;
        this.fileName = fileName;
    }

    @Override
    public String getFileName() {
        return fileName;
    }

    @Override
    protected void sendData(OutputStream out) throws IOException {
        out.write(bytes);
    }

    @Override
    protected long getDataLength() {
        return bytes.length;
    }

    public byte[] getBytes() {
        return bytes;
    }

    @Override
    public long write(WritableByteChannel target, byte[] boundary) throws IOException {
        FilePartStallHandler handler = new FilePartStallHandler(getStalledTime(), this);

        try {
            handler.start();

            long length = MultipartUtils.writeBytesToChannel(target, generateFileStart(boundary));
            length += MultipartUtils.writeBytesToChannel(target, bytes);
            length += MultipartUtils.writeBytesToChannel(target, generateFileEnd());

            return length;
        } finally {
            handler.completed();
        }
    }
}
