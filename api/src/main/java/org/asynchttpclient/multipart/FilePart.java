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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FilePart extends AbstractFilePart {

    private static final Logger LOGGER = LoggerFactory.getLogger(FilePart.class);

    private final File file;
    private final String fileName;

    public FilePart(String name, File file) {
        this(name, file, null, null);
    }

    public FilePart(String name, File file, String contentType) {
        this(name, file, null, contentType, null);
    }

    public FilePart(String name, File file, String contentType, String charset) {
        this(name, file, null, contentType, charset, null);
    }

    public FilePart(String name, File file, String contentType, String charset, String fileName) {
        this(name, file, null, contentType, charset, fileName);
    }

    public FilePart(String name, File file, String contentType, String charset, String fileName, String contentId) {
        super(name, contentType, charset, contentId);
        this.file = file;
        if (file == null) {
            throw new NullPointerException("file");
        }
        if (!file.isFile()) {
            throw new IllegalArgumentException("File is not a normal file " + file.getAbsolutePath());
        }
        if (!file.canRead()) {
            throw new IllegalArgumentException("File is not readable " + file.getAbsolutePath());
        }
        this.fileName = fileName != null ? fileName : file.getName();
    }

    @Override
    public String getFileName() {
        return fileName;
    }

    @Override
    protected void sendData(OutputStream out) throws IOException {
        if (getDataLength() == 0) {

            // this file contains no data, so there is nothing to send.
            // we don't want to create a zero length buffer as this will
            // cause an infinite loop when reading.
            return;
        }

        byte[] tmp = new byte[4096];
        InputStream instream = new FileInputStream(file);
        try {
            int len;
            while ((len = instream.read(tmp)) >= 0) {
                out.write(tmp, 0, len);
            }
        } finally {
            // we're done with the stream, close it
            instream.close();
        }
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

        long l = file.length();
        int fileLength = 0;
        long nWrite = 0;
        // FIXME why sync?
        try {
            synchronized (fc) {
                while (fileLength != l) {
                    if (handler.isFailed()) {
                        LOGGER.debug("Stalled error");
                        throw new FileUploadStalledException();
                    }
                    try {
                        nWrite = fc.transferTo(fileLength, l, target);

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
                    } catch (IOException ex) {
                        String message = ex.getMessage();

                        // http://bugs.sun.com/view_bug.do?bug_id=5103988
                        if (message != null && message.equalsIgnoreCase("Resource temporarily unavailable")) {
                            try {
                                fc.wait(1000);
                            } catch (InterruptedException e) {
                                LOGGER.trace(e.getMessage(), e);
                            }
                            LOGGER.warn("Experiencing NIO issue http://bugs.sun.com/view_bug.do?bug_id=5103988. Retrying");
                            continue;
                        } else {
                            throw ex;
                        }
                    }
                    fileLength += nWrite;
                }
            }
        } finally {
            handler.completed();
            raf.close();
        }

        length += MultipartUtils.writeBytesToChannel(target, generateFileEnd());

        return length;
    }
}
