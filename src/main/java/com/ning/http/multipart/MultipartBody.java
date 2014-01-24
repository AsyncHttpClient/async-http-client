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
package com.ning.http.multipart;

import com.ning.http.client.RandomAccessBody;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MultipartBody implements RandomAccessBody {

    private final static Logger logger = LoggerFactory.getLogger(MultipartBody.class);

    private final byte[] boundary;
    private final long contentLength;
    private final List<com.ning.http.client.Part> parts;
    private final List<RandomAccessFile> files = new ArrayList<RandomAccessFile>();

    private int startPart = 0;
    private ByteArrayInputStream currentStream;
    private int currentStreamPosition = -1;
    private boolean endWritten = false;
    private boolean doneWritingParts = false;
    private FileLocation fileLocation = FileLocation.NONE;
    private FilePart currentFilePart;
    private FileChannel currentFileChannel;

    enum FileLocation {NONE, START, MIDDLE, END}

    public MultipartBody(List<com.ning.http.client.Part> parts, String contentType, long contentLength) {
        this.boundary = MultipartEncodingUtil.getAsciiBytes(contentType.substring(contentType.indexOf("boundary=") + "boundary=".length()));
        this.parts = parts;
        this.contentLength = contentLength;
    }

    public void close() throws IOException {
        for (RandomAccessFile file : files) {
            file.close();
        }
    }

    public long getContentLength() {
        return contentLength;
    }

    public long read(ByteBuffer buffer) throws IOException {
        try {
            int overallLength = 0;

            final int maxLength = buffer.remaining();

            if (startPart == parts.size() && endWritten) {
                return -1;
            }

            boolean full = false;
            while (!full && !doneWritingParts) {
                com.ning.http.client.Part part = null;
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
                } else if (part instanceof com.ning.http.client.StringPart) {
                    StringPart currentPart = generateClientStringpart(part);

                    initializeStringPart(currentPart);

                    startPart++;
                } else if (part instanceof FilePart) {
                    if (fileLocation == FileLocation.NONE) {
                        currentFilePart = (FilePart) part;
                        initializeFilePart(currentFilePart);
                    } else if (fileLocation == FileLocation.START) {
                        initializeFileBody(currentFilePart);
                    } else if (fileLocation == FileLocation.MIDDLE) {
                        initializeFileEnd(currentFilePart);
                    } else if (fileLocation == FileLocation.END) {
                        startPart++;
                        fileLocation = FileLocation.NONE;
                        if (startPart == parts.size() && currentStream.available() == 0) {
                            doneWritingParts = true;
                        }
                    }
                } else if (part instanceof com.ning.http.client.FilePart) {
                    if (fileLocation == FileLocation.NONE) {
                        currentFilePart = generateClientFilePart(part);
                        initializeFilePart(currentFilePart);
                    } else if (fileLocation == FileLocation.START) {
                        initializeFileBody(currentFilePart);
                    } else if (fileLocation == FileLocation.MIDDLE) {
                        initializeFileEnd(currentFilePart);
                    } else if (fileLocation == FileLocation.END) {
                        startPart++;
                        fileLocation = FileLocation.NONE;
                        if (startPart == parts.size() && currentStream.available() == 0) {
                            doneWritingParts = true;
                        }
                    }
                } else if (part instanceof com.ning.http.client.ByteArrayPart) {
                    com.ning.http.client.ByteArrayPart bytePart =
                            (com.ning.http.client.ByteArrayPart) part;

                    if (fileLocation == FileLocation.NONE) {
                        currentFilePart =
                                generateClientByteArrayPart(bytePart);

                        initializeFilePart(currentFilePart);
                    } else if (fileLocation == FileLocation.START) {
                        initializeByteArrayBody(currentFilePart);
                    } else if (fileLocation == FileLocation.MIDDLE) {
                        initializeFileEnd(currentFilePart);
                    } else if (fileLocation == FileLocation.END) {
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

                    initializeBuffer(endWriter.toByteArray());
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
            logger.info("read exception", e);
            return 0;
        }
    }

    private void initializeByteArrayBody(FilePart filePart)
            throws IOException {

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        filePart.sendData(output);

        initializeBuffer(output.toByteArray());

        fileLocation = FileLocation.MIDDLE;
    }

    private void initializeFileEnd(FilePart currentPart)
            throws IOException {

        ByteArrayOutputStream output = generateFileEnd(currentPart);

        initializeBuffer(output.toByteArray());

        fileLocation = FileLocation.END;

    }

    private void initializeFileBody(FilePart currentPart)
            throws IOException {

        if (currentPart.getSource() instanceof FilePartSource) {

            FilePartSource source = (FilePartSource) currentPart.getSource();

            File file = source.getFile();

            RandomAccessFile raf = new RandomAccessFile(file, "r");
            files.add(raf);

            currentFileChannel = raf.getChannel();

        } else {
            // ByteArrayPartSource
            PartSource partSource = currentPart.getSource();

            InputStream stream = partSource.createInputStream();

            byte[] bytes = new byte[(int) partSource.getLength()];

            stream.read(bytes);

            currentStream = new ByteArrayInputStream(bytes);

            currentStreamPosition = 0;
        }

        fileLocation = FileLocation.MIDDLE;
    }

    private void initializeFilePart(FilePart filePart)
            throws IOException {

        ByteArrayOutputStream output = generateFileStart(filePart);

        initializeBuffer(output.toByteArray());

        fileLocation = FileLocation.START;
    }

    private void initializeStringPart(StringPart currentPart)
            throws IOException {

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        Part.sendPart(outputStream, currentPart, boundary);

        initializeBuffer(outputStream.toByteArray());
    }

    private int writeToBuffer(ByteBuffer buffer, int length)
            throws IOException {

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

    private void initializeBuffer(byte[] bytes)
            throws IOException {

        currentStream = new ByteArrayInputStream(bytes);

        currentStreamPosition = 0;

    }

    public long transferTo(long position, long count, WritableByteChannel target)
            throws IOException {

        long overallLength = 0;

        if (startPart == parts.size()) {
            return contentLength;
        }

        int tempPart = startPart;

        for (com.ning.http.client.Part part : parts) {
            if (part instanceof Part) {
                overallLength += handleMultiPart(target, (Part) part);
            } else {
                overallLength += handleClientPart(target, part);
            }

            tempPart++;
        }
        ByteArrayOutputStream endWriter = new ByteArrayOutputStream();

        Part.sendMessageEnd(endWriter, boundary);

        overallLength += writeToTarget(target, endWriter.toByteArray());

        startPart = tempPart;

        return overallLength;
    }

    private long handleClientPart(
            WritableByteChannel target, com.ning.http.client.Part part) throws IOException {

        if (part.getClass().equals(com.ning.http.client.StringPart.class)) {
            StringPart currentPart = generateClientStringpart(part);

            return handleStringPart(target, currentPart);
        } else if (part.getClass().equals(com.ning.http.client.FilePart.class)) {
            FilePart filePart = generateClientFilePart(part);

            return handleFilePart(target, filePart);
        } else if (part.getClass().equals(com.ning.http.client.ByteArrayPart.class)) {
            com.ning.http.client.ByteArrayPart bytePart = (com.ning.http.client.ByteArrayPart) part;

            FilePart filePart = generateClientByteArrayPart(bytePart);

            return handleByteArrayPart(target, filePart, bytePart.getData());
        }

        return 0;
    }

    private FilePart generateClientByteArrayPart(
            com.ning.http.client.ByteArrayPart bytePart) {
        ByteArrayPartSource source = new ByteArrayPartSource(bytePart.getFileName(), bytePart.getData());

        FilePart filePart = new FilePart(bytePart.getName(), source, bytePart.getMimeType(), bytePart.getCharSet());
        return filePart;
    }

    private FilePart generateClientFilePart(com.ning.http.client.Part part)
            throws FileNotFoundException {
        com.ning.http.client.FilePart currentPart = (com.ning.http.client.FilePart) part;

        FilePart filePart = new FilePart(currentPart.getName(), currentPart.getFile(), currentPart.getMimeType(), currentPart.getCharSet());
        return filePart;
    }

    private StringPart generateClientStringpart(com.ning.http.client.Part part) {
        com.ning.http.client.StringPart stringPart = (com.ning.http.client.StringPart) part;

        StringPart currentPart = new StringPart(stringPart.getName(), stringPart.getValue(), stringPart.getCharset());
        return currentPart;
    }

    private long handleByteArrayPart(WritableByteChannel target,
                                     FilePart filePart, byte[] data) throws IOException {

        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Part.sendPart(output, filePart, boundary);
        return writeToTarget(target, output.toByteArray());
    }

    private long handleFileEnd(WritableByteChannel target, FilePart filePart)
            throws IOException {

        ByteArrayOutputStream endOverhead = generateFileEnd(filePart);

        return this.writeToTarget(target, endOverhead.toByteArray());
    }

    private ByteArrayOutputStream generateFileEnd(FilePart filePart)
            throws IOException {
        ByteArrayOutputStream endOverhead = new ByteArrayOutputStream();

        filePart.sendEnd(endOverhead);
        return endOverhead;
    }

    private long handleFileHeaders(WritableByteChannel target, FilePart filePart) throws IOException {

        ByteArrayOutputStream overhead = generateFileStart(filePart);

        return writeToTarget(target, overhead.toByteArray());
    }

    private ByteArrayOutputStream generateFileStart(FilePart filePart)
            throws IOException {
        ByteArrayOutputStream overhead = new ByteArrayOutputStream();

        filePart.sendStart(overhead, boundary);
        filePart.sendDispositionHeader(overhead);
        filePart.sendContentTypeHeader(overhead);
        filePart.sendTransferEncodingHeader(overhead);
        filePart.sendContentIdHeader(overhead);
        filePart.sendEndOfHeader(overhead);
        return overhead;
    }

    private long handleFilePart(WritableByteChannel target, FilePart filePart) throws IOException {
    	FilePartStallHandler handler = new FilePartStallHandler(
    		filePart.getStalledTime(), filePart);

    	handler.start();

        if (filePart.getSource() instanceof FilePartSource) {
            int length = 0;

            length += handleFileHeaders(target, filePart);
            FilePartSource source = (FilePartSource) filePart.getSource();

            File file = source.getFile();

            RandomAccessFile raf = new RandomAccessFile(file, "r");
            files.add(raf);

            FileChannel fc = raf.getChannel();

            long l = file.length();
            int fileLength = 0;
            long nWrite = 0;
            synchronized (fc) {
                while (fileLength != l) {
                	if(handler.isFailed()) {
                		logger.debug("Stalled error");
                        throw new FileUploadStalledException();
                	}
                    try {
                        nWrite = fc.transferTo(fileLength, l, target);

                        if (nWrite == 0) {
                            logger.info("Waiting for writing...");
                            try {
                                fc.wait(50);
                            } catch (InterruptedException e) {
                                logger.trace(e.getMessage(), e);
                            }
                        }
                        else {
                        	handler.writeHappened();
                        }
                    } catch (IOException ex) {
                        String message = ex.getMessage();

                        // http://bugs.sun.com/view_bug.do?bug_id=5103988
                        if (message != null && message.equalsIgnoreCase("Resource temporarily unavailable")) {
                            try {
                                fc.wait(1000);
                            } catch (InterruptedException e) {
                                logger.trace(e.getMessage(), e);
                            }
                            logger.warn("Experiencing NIO issue http://bugs.sun.com/view_bug.do?bug_id=5103988. Retrying");
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
        } else {
            return handlePartSource(target, filePart);
        }
    }

    private long handlePartSource(WritableByteChannel target, FilePart filePart) throws IOException {

        int length = 0;

        length += handleFileHeaders(target, filePart);

        PartSource partSource = filePart.getSource();

        InputStream stream = partSource.createInputStream();

        try {
            int nRead = 0;
            while (nRead != -1) {
                // Do not buffer the entire monster in memory.
                byte[] bytes = new byte[8192];
                nRead = stream.read(bytes);
                if (nRead > 0) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(nRead);
                    bos.write(bytes, 0, nRead);
                    length += writeToTarget(target, bos.toByteArray());
                }
            }
        } finally {
            stream.close();
        }
        length += handleFileEnd(target, filePart);

        return length;
    }

    private long handleStringPart(WritableByteChannel target, StringPart currentPart) throws IOException {

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        Part.sendPart(outputStream, currentPart, boundary);

        return writeToTarget(target, outputStream.toByteArray());
    }

    private long handleMultiPart(WritableByteChannel target, Part currentPart) throws IOException {

        if (currentPart.getClass().equals(StringPart.class)) {
            return handleStringPart(target, (StringPart) currentPart);
        } else if (currentPart.getClass().equals(FilePart.class)) {
            FilePart filePart = (FilePart) currentPart;

            return handleFilePart(target, filePart);
        }
        return 0;
    }

    private long writeToTarget(WritableByteChannel target, byte[] bytes)
            throws IOException {

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
                        logger.info("Waiting for writing...");
                        try {
                            bytes.wait(1000);
                        } catch (InterruptedException e) {
                            logger.trace(e.getMessage(), e);
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
