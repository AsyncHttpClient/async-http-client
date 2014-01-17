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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.asynchttpclient.RandomAccessBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MultipartBody implements RandomAccessBody {

    private final static Logger LOGGER = LoggerFactory.getLogger(MultipartBody.class);

    private final byte[] boundary;
    private final long contentLength;
    private final String contentType;
    private final List<Part> parts;
    // FIXME why keep all of them open?
    private final List<RandomAccessFile> files = new ArrayList<RandomAccessFile>();

    private int startPart = 0;
    private ByteArrayInputStream currentStream;
    private int currentStreamPosition = -1;
    private boolean endWritten = false;
    private boolean doneWritingParts = false;
    private FileLocation fileLocation = FileLocation.NONE;
    private AbstractFilePart currentFilePart;
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
        for (RandomAccessFile file : files) {
            file.close();
        }
    }

    public long getContentLength() {
        return contentLength;
    }

    public String getContentType() {
        return contentType;
    }

    public long read(ByteBuffer buffer) throws IOException {
        try {
            int overallLength = 0;

            int maxLength = buffer.remaining();

            if (startPart == parts.size() && endWritten) {
                return -1;
            }

            boolean full = false;
            while (!full && !doneWritingParts) {
                Part part = null;
                if (startPart < parts.size()) {
                    part = parts.get(startPart);
                }
                if (currentFileChannel != null) {
                    overallLength += currentFileChannel.read(buffer);

                    if (currentFileChannel.position() == currentFileChannel.size()) {
                        currentFileChannel.close();
                        currentFileChannel = null;
                    }

                    if (overallLength == maxLength) {
                        full = true;
                    }
                } else if (currentStreamPosition > -1) {
                    overallLength += writeToBuffer(buffer, maxLength - overallLength);

                    if (overallLength == maxLength) {
                        full = true;
                    }
                    if (startPart == parts.size() && currentStream.available() == 0) {
                        doneWritingParts = true;
                    }
                } else if (part instanceof StringPart) {
                    StringPart currentPart = (StringPart) part;

                    initializeStringPart(currentPart);
                    startPart++;

                } else if (part instanceof AbstractFilePart) {

                    switch (fileLocation) {
                    case NONE:
                        currentFilePart = (AbstractFilePart) part;
                        initializeFilePart(currentFilePart);
                        break;
                    case START:
                        initializeFileBody(currentFilePart);
                        break;
                    case MIDDLE:
                        initializeFileEnd(currentFilePart);
                        break;
                    case END:
                        startPart++;
                        fileLocation = FileLocation.NONE;
                        if (startPart == parts.size() && currentStream.available() == 0) {
                            doneWritingParts = true;
                        }
                    }
                }
            }

            if (doneWritingParts) {
                if (currentStreamPosition == -1) {
                    ByteArrayOutputStream endWriter = new ByteArrayOutputStream();

                    Part.sendMessageEnd(endWriter, boundary);

                    initializeBuffer(endWriter);
                }

                if (currentStreamPosition > -1) {
                    overallLength += writeToBuffer(buffer, maxLength - overallLength);

                    if (currentStream.available() == 0) {
                        currentStream.close();
                        currentStreamPosition = -1;
                        endWritten = true;
                    }
                }
            }
            return overallLength;

        } catch (Exception e) {
            LOGGER.info("read exception", e);
            return 0;
        }
    }

    private void initializeFileEnd(AbstractFilePart currentPart) throws IOException {

        ByteArrayOutputStream output = generateFileEnd(currentPart);

        initializeBuffer(output);

        fileLocation = FileLocation.END;

    }

    private void initializeFileBody(AbstractFilePart currentPart) throws IOException {

        if (currentPart instanceof FilePart) {
            RandomAccessFile raf = new RandomAccessFile(FilePart.class.cast(currentPart).getFile(), "r");
            files.add(raf);

            currentFileChannel = raf.getChannel();

        } else {
            currentStream = new ByteArrayInputStream(ByteArrayPart.class.cast(currentPart).getBytes());
            currentStreamPosition = 0;
        }

        fileLocation = FileLocation.MIDDLE;
    }

    private void initializeFilePart(AbstractFilePart filePart) throws IOException {

        ByteArrayOutputStream output = generateFileStart(filePart);
        initializeBuffer(output);
        fileLocation = FileLocation.START;
    }

    private void initializeStringPart(StringPart currentPart) throws IOException {

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Part.sendPart(outputStream, currentPart, boundary);
        initializeBuffer(outputStream);
    }

    private int writeToBuffer(ByteBuffer buffer, int length) throws IOException {

        int available = currentStream.available();

        int writeLength = Math.min(available, length);

        byte[] bytes = new byte[writeLength];

        currentStream.read(bytes);

        buffer.put(bytes);

        if (available <= length) {
            currentStream.close();
            currentStreamPosition = -1;
        } else {
            currentStreamPosition += writeLength;
        }

        return writeLength;
    }

    private void initializeBuffer(ByteArrayOutputStream outputStream) throws IOException {
        currentStream = new ByteArrayInputStream(outputStream.toByteArray());
        currentStreamPosition = 0;
    }

    public long transferTo(long position, long count, WritableByteChannel target) throws IOException {

        long overallLength = 0;

        if (startPart == parts.size()) {
            return contentLength;
        }

        int tempPart = startPart;

        for (Part part : parts) {
            overallLength += handleMultiPart(target, part);
            tempPart++;
        }
        ByteArrayOutputStream endWriter = new ByteArrayOutputStream();
        Part.sendMessageEnd(endWriter, boundary);
        overallLength += writeToTarget(target, endWriter.toByteArray());

        startPart = tempPart;

        return overallLength;
    }

    private long handleFileEnd(WritableByteChannel target, AbstractFilePart filePart) throws IOException {
        ByteArrayOutputStream endOverhead = generateFileEnd(filePart);
        return this.writeToTarget(target, endOverhead.toByteArray());
    }

    private ByteArrayOutputStream generateFileEnd(AbstractFilePart filePart) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OutputStreamPartVisitor visitor = new OutputStreamPartVisitor(out);
        filePart.visitEnd(visitor);
        return out;
    }

    private long handleFileHeaders(WritableByteChannel target, AbstractFilePart filePart) throws IOException {
        ByteArrayOutputStream overhead = generateFileStart(filePart);
        return writeToTarget(target, overhead.toByteArray());
    }

    private ByteArrayOutputStream generateFileStart(AbstractFilePart filePart) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OutputStreamPartVisitor visitor = new OutputStreamPartVisitor(out);
        filePart.visitStart(visitor, boundary);
        filePart.visitDispositionHeader(visitor);
        filePart.visitContentTypeHeader(visitor);
        filePart.visitTransferEncodingHeader(visitor);
        filePart.visitContentIdHeader(visitor);
        filePart.visitEndOfHeader(visitor);

        return out;
    }

    private long handleFilePart(WritableByteChannel target, FilePart filePart) throws IOException {

        FilePartStallHandler handler = new FilePartStallHandler(filePart.getStalledTime(), filePart);

        handler.start();

        int length = 0;

        length += handleFileHeaders(target, filePart);
        File file = FilePart.class.cast(filePart).getFile();

        RandomAccessFile raf = new RandomAccessFile(file, "r");
        files.add(raf);

        FileChannel fc = raf.getChannel();

        long l = file.length();
        int fileLength = 0;
        long nWrite = 0;
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
        handler.completed();

        fc.close();

        length += handleFileEnd(target, filePart);

        return length;
    }

    private long handleByteArrayPart(WritableByteChannel target, ByteArrayPart part) throws IOException {

        FilePartStallHandler handler = new FilePartStallHandler(part.getStalledTime(), part);

        handler.start();

        int length = 0;

        length += handleFileHeaders(target, part);

        byte[] bytes = ByteArrayPart.class.cast(part).getBytes();
        writeToTarget(target, bytes);
        length += handleFileEnd(target, part);

        return length;
    }

    private long handleStringPart(WritableByteChannel target, StringPart currentPart) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Part.sendPart(outputStream, currentPart, boundary);
        return writeToTarget(target, outputStream.toByteArray());
    }

    private long handleMultiPart(WritableByteChannel target, Part currentPart) throws IOException {

        if (currentPart instanceof StringPart) {
            return handleStringPart(target, StringPart.class.cast(currentPart));
        } else if (currentPart instanceof FilePart) {
            return handleFilePart(target, FilePart.class.cast(currentPart));
        } else if (currentPart instanceof ByteArrayPart) {
            return handleByteArrayPart(target, ByteArrayPart.class.cast(currentPart));
        } else {
            throw new IllegalArgumentException("Can't handle part of type " + currentPart.getClass());
        }
    }

    private long writeToTarget(WritableByteChannel target, byte[] bytes) throws IOException {

        int written = 0;
        int maxSpin = 0;
        synchronized (bytes) {
            ByteBuffer message = ByteBuffer.wrap(bytes);

            if (target instanceof SocketChannel) {
                final Selector selector = Selector.open();
                try {
                    final SocketChannel channel = (SocketChannel) target;
                    channel.register(selector, SelectionKey.OP_WRITE);

                    while (written < bytes.length) {
                        selector.select(1000);
                        maxSpin++;
                        final Set<SelectionKey> selectedKeys = selector.selectedKeys();

                        for (SelectionKey key : selectedKeys) {
                            if (key.isWritable()) {
                                written += target.write(message);
                                maxSpin = 0;
                            }
                        }
                        if (maxSpin >= 10) {
                            throw new IOException("Unable to write on channel " + target);
                        }
                    }
                } finally {
                    selector.close();
                }
            } else {
                while ((target.isOpen()) && (written < bytes.length)) {
                    long nWrite = target.write(message);
                    written += nWrite;
                    if (nWrite == 0 && maxSpin++ < 10) {
                        LOGGER.info("Waiting for writing...");
                        try {
                            bytes.wait(1000);
                        } catch (InterruptedException e) {
                            LOGGER.trace(e.getMessage(), e);
                        }
                    } else {
                        if (maxSpin >= 10) {
                            throw new IOException("Unable to write on channel " + target);
                        }
                        maxSpin = 0;
                    }
                }
            }
        }
        return written;
    }
}
