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
package com.ning.http.client.multipart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.RandomAccessBody;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;

public class MultipartBody implements RandomAccessBody {

    private final static Logger LOGGER = LoggerFactory.getLogger(MultipartBody.class);

    private final byte[] boundary;
    private final long contentLength;
    private final String contentType;
    private final List<Part> parts;
    private final List<RandomAccessFile> pendingOpenFiles = new ArrayList<>();

    private boolean transfertDone = false;

    private int currentPart = 0;
    private byte[] currentBytes;
    private int currentBytesPosition = -1;
    private boolean doneWritingParts = false;
    private FileLocation fileLocation = FileLocation.NONE;
    private FileChannel currentFileChannel;

    enum FileLocation {
        NONE, START, MIDDLE, END
    }

    public MultipartBody(List<Part> parts, String contentType, long contentLength, byte[] boundary) {
        this.boundary = boundary;
        this.contentLength = contentLength;
        this.contentType = contentType;
        this.parts = parts;
    }

    public void close() throws IOException {
        for (RandomAccessFile file : pendingOpenFiles) {
            file.close();
        }
    }

    public long getContentLength() {
        return contentLength;
    }

    public String getContentType() {
        return contentType;
    }

    public byte[] getBoundary() {
        return boundary;
    }

    // RandomAccessBody API, suited for HTTP but not for HTTPS
    public long transferTo(long position, WritableByteChannel target) throws IOException {

        if (transfertDone) {
            return -1;
        }

        long overallLength = 0;

        for (Part part : parts) {
            overallLength += part.write(target, boundary);
        }

        overallLength += MultipartUtils.writeBytesToChannel(target, MultipartUtils.getMessageEnd(boundary));

        transfertDone = true;

        return overallLength;
    }

    // Regular Body API
    public long read(ByteBuffer buffer) throws IOException {
        try {
            int overallLength = 0;

            int maxLength = buffer.remaining();

            if (currentPart == parts.size() && transfertDone) {
                return -1;
            }

            boolean full = false;

            while (!full && !doneWritingParts) {
                Part part = null;

                if (currentPart < parts.size()) {
                    part = parts.get(currentPart);
                }
                if (currentFileChannel != null) {
                    overallLength += writeCurrentFile(buffer);
                    full = overallLength == maxLength;

                } else if (currentBytesPosition > -1) {
                    overallLength += writeCurrentBytes(buffer, maxLength - overallLength);
                    full = overallLength == maxLength;

                    if (currentPart == parts.size() && currentBytesFullyRead()) {
                        doneWritingParts = true;
                    }

                } else if (part instanceof StringPart) {
                    StringPart stringPart = (StringPart) part;
                    // set new bytes, not full, so will loop to writeCurrentBytes above
                    initializeCurrentBytes(stringPart.getBytes(boundary));
                    currentPart++;

                } else if (part instanceof AbstractFilePart) {

                    AbstractFilePart filePart = (AbstractFilePart) part;

                    switch (fileLocation) {
                    case NONE:
                        // set new bytes, not full, so will loop to writeCurrentBytes above
                        initializeCurrentBytes(filePart.generateFileStart(boundary));
                        fileLocation = FileLocation.START;
                        break;
                    case START:
                        // set current file channel so code above executes first
                        initializeFileBody(filePart);
                        fileLocation = FileLocation.MIDDLE;
                        break;
                    case MIDDLE:
                        initializeCurrentBytes(filePart.generateFileEnd());
                        fileLocation = FileLocation.END;
                        break;
                    case END:
                        currentPart++;
                        fileLocation = FileLocation.NONE;
                        if (currentPart == parts.size()) {
                            doneWritingParts = true;
                        }
                    }
                }
            }

            if (doneWritingParts) {
                if (currentBytesPosition == -1) {
                    initializeCurrentBytes(MultipartUtils.getMessageEnd(boundary));
                }

                if (currentBytesPosition > -1) {
                    overallLength += writeCurrentBytes(buffer, maxLength - overallLength);

                    if (currentBytesFullyRead()) {
                        currentBytes = null;
                        currentBytesPosition = -1;
                        transfertDone = true;
                    }
                }
            }
            return overallLength;

        } catch (Exception e) {
            LOGGER.error("Read exception", e);
            return 0;
        }
    }

    private boolean currentBytesFullyRead() {
        return currentBytes == null || currentBytesPosition >= currentBytes.length - 1;
    }

    private void initializeFileBody(AbstractFilePart part) throws IOException {

        if (part instanceof FilePart) {
            RandomAccessFile raf = new RandomAccessFile(FilePart.class.cast(part).getFile(), "r");
            pendingOpenFiles.add(raf);
            currentFileChannel = raf.getChannel();

        } else if (part instanceof ByteArrayPart) {
            initializeCurrentBytes(ByteArrayPart.class.cast(part).getBytes());

        } else {
            throw new IllegalArgumentException("Unknow AbstractFilePart type");
        }
    }

    private void initializeCurrentBytes(byte[] bytes) throws IOException {
        currentBytes = bytes;
        currentBytesPosition = 0;
    }

    private int writeCurrentFile(ByteBuffer buffer) throws IOException {

        int read = currentFileChannel.read(buffer);

        if (currentFileChannel.position() == currentFileChannel.size()) {

            currentFileChannel.close();
            currentFileChannel = null;

            int currentFile = pendingOpenFiles.size() - 1;
            pendingOpenFiles.get(currentFile).close();
            pendingOpenFiles.remove(currentFile);
        }

        return read;
    }

    private int writeCurrentBytes(ByteBuffer buffer, int length) throws IOException {

        if (currentBytes.length == 0) {
            currentBytesPosition = -1;
            currentBytes = null;
            return 0;
        }

        int available = currentBytes.length - currentBytesPosition;

        int writeLength = Math.min(available, length);

        if (writeLength > 0) {
            buffer.put(currentBytes, currentBytesPosition, writeLength);

            if (available <= length) {
                currentBytesPosition = -1;
                currentBytes = null;
            } else {
                currentBytesPosition += writeLength;
            }
        }

        return writeLength;
    }
}
