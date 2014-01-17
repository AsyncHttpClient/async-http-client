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
package org.asynchttpclient.multipart;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;

import org.asynchttpclient.RandomAccessBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MultipartBody implements RandomAccessBody {

    private final static Logger LOGGER = LoggerFactory.getLogger(MultipartBody.class);

    private final byte[] boundary;
    private final long contentLength;
    private final String contentType;
    private final List<Part> parts;
    private final List<RandomAccessFile> pendingOpenFiles = new ArrayList<RandomAccessFile>();

    private boolean transfertDone = false;

    private int currentPart = 0;
    private ByteArrayInputStream currentStream;
    private int currentStreamPosition = -1;
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

    // RandomAccessBody API, suited for HTTP but not for HTTPS
    public long transferTo(long position, long count, WritableByteChannel target) throws IOException {

        long overallLength = 0;

        if (transfertDone) {
            return contentLength;
        }

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

                } else if (currentStreamPosition > -1) {
                    overallLength += writeCurrentStream(buffer, maxLength - overallLength);
                    full = overallLength == maxLength;

                    if (currentPart == parts.size() && currentStream.available() == 0) {
                        doneWritingParts = true;
                    }

                } else if (part instanceof StringPart) {
                    StringPart stringPart = (StringPart) part;
                    initializeNewCurrentStream(stringPart.getBytes(boundary));
                    currentPart++;

                } else if (part instanceof AbstractFilePart) {

                    AbstractFilePart filePart = (AbstractFilePart) part;

                    switch (fileLocation) {
                    case NONE:
                        // create new stream, not full, so will loop to writeCurrentStream above
                        initializeNewCurrentStream(filePart.generateFileStart(boundary));
                        fileLocation = FileLocation.START;
                        break;
                    case START:
                        // set current file channel so code above executes first
                        initializeFileBody(filePart);
                        fileLocation = FileLocation.MIDDLE;
                        break;
                    case MIDDLE:
                        initializeNewCurrentStream(filePart.generateFileEnd());
                        fileLocation = FileLocation.END;
                        break;
                    case END:
                        currentPart++;
                        fileLocation = FileLocation.NONE;
                        if (currentPart == parts.size() && currentStream.available() == 0) {
                            doneWritingParts = true;
                        }
                    }
                }
            }

            if (doneWritingParts) {
                if (currentStreamPosition == -1) {
                    initializeNewCurrentStream(MultipartUtils.getMessageEnd(boundary));
                }

                if (currentStreamPosition > -1) {
                    overallLength += writeCurrentStream(buffer, maxLength - overallLength);

                    if (currentStream.available() == 0) {
                        currentStream.close();
                        currentStreamPosition = -1;
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

    private void initializeFileBody(AbstractFilePart part) throws IOException {

        if (part instanceof FilePart) {
            RandomAccessFile raf = new RandomAccessFile(FilePart.class.cast(part).getFile(), "r");
            pendingOpenFiles.add(raf);
            currentFileChannel = raf.getChannel();

        } else if (part instanceof ByteArrayPart) {
            initializeNewCurrentStream(ByteArrayPart.class.cast(part).getBytes());

        } else {
            throw new IllegalArgumentException("Unknow AbstractFilePart type");
        }
    }

    private void initializeNewCurrentStream(byte[] bytes) throws IOException {
        currentStream = new ByteArrayInputStream(bytes);
        currentStreamPosition = 0;
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

    private int writeCurrentStream(ByteBuffer buffer, int length) throws IOException {

        int available = currentStream.available();

        int writeLength = Math.min(available, length);

        if (writeLength > 0) {
            byte[] bytes = new byte[writeLength];

            currentStream.read(bytes);
            buffer.put(bytes);

            if (available <= length) {
                currentStream.close();
                currentStreamPosition = -1;
            } else {
                currentStreamPosition += writeLength;
            }
        }

        return writeLength;
    }
}
