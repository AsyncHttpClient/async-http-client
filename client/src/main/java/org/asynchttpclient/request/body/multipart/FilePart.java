/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.request.body.multipart;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FilePart extends AbstractFilePart {

    private static final Logger LOGGER = LoggerFactory.getLogger(FilePart.class);

    private final File file;

    public FilePart(String name, File file) {
        this(name, file, null);
    }

    public FilePart(String name, File file, String contentType) {
        this(name, file, contentType, null);
    }

    public FilePart(String name, File file, String contentType, Charset charset) {
        this(name, file, contentType, charset, null);
    }

    public FilePart(String name, File file, String contentType, Charset charset, String fileName) {
        this(name, file, contentType, charset, fileName, null);
    }

    public FilePart(String name, File file, String contentType, Charset charset, String fileName, String contentId) {
        this(name, file, contentType, charset, fileName, contentId, null);
    }

    public FilePart(String name, File file, String contentType, Charset charset, String fileName, String contentId, String transferEncoding) {
        super(name, contentType, charset, contentId, transferEncoding);
        if (file == null)
            throw new NullPointerException("file");
        if (!file.isFile())
            throw new IllegalArgumentException("File is not a normal file " + file.getAbsolutePath());
        if (!file.canRead())
            throw new IllegalArgumentException("File is not readable " + file.getAbsolutePath());
        this.file = file;
        setFileName(fileName != null ? fileName : file.getName());
    }
    
    @Override
    protected long getDataLength() {
        return file.length();
    }

    public File getFile() {
        return file;
    }

    @Override
    public long write(WritableByteChannel target, byte[] boundary) throws IOException {
        FilePartStallHandler handler = new FilePartStallHandler(getStalledTime(), this);

        handler.start();

        int length = 0;

        length += MultipartUtils.writeBytesToChannel(target, generateFileStart(boundary));

        RandomAccessFile raf = new RandomAccessFile(file, "r");
        FileChannel fc = raf.getChannel();

        final long fileLength = file.length();
        int position = 0;
        long nWrite = 0;
        try {
            // FIXME why sync?
            synchronized (fc) {
                while (position != fileLength) {
                    if (handler.isFailed()) {
                        LOGGER.debug("Stalled error");
                        throw new FileUploadStalledException();
                    }
                    nWrite = fc.transferTo(position, fileLength, target);

                    if (nWrite == 0) {
                        LOGGER.info("Waiting for writing...");
                        try {
                            fc.wait(50);
                        } catch (InterruptedException e) {
                            LOGGER.trace(e.getMessage(), e);
                        }
                    } else {
                        handler.writeHappened();
                    }
                    position += nWrite;
                }
            }
        } finally {
            handler.completed();
            raf.close();
        }

        length += fileLength;
        length += MultipartUtils.writeBytesToChannel(target, generateFileEnd());

        return length;
    }
}
