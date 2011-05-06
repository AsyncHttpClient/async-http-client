/*
 * Copyright (c) 2010-2011 Sonatype, Inc. All rights reserved.
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;

public class MultipartBody implements RandomAccessBody {

    private byte[] boundary;
    private long contentLength;
    private List<com.ning.http.client.Part> parts;
    private List<RandomAccessFile> files;
    private int startPart;
    private final static Logger logger = LoggerFactory.getLogger(MultipartBody.class);

    public MultipartBody(List<com.ning.http.client.Part> parts, String boundary, String contentLength) {
        this.boundary = MultipartEncodingUtil.getAsciiBytes(boundary.substring("multipart/form-data; boundary=".length()));
        this.contentLength = Long.parseLong(contentLength);
        this.parts = parts;

        files = new ArrayList<RandomAccessFile>();

        startPart = 0;
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
        // TODO Not implemented
        return 0;
    }

    public long transferTo(long position, long count, WritableByteChannel target)
            throws IOException {

        long overallLength = 0;

        if (startPart == parts.size()) {
            return overallLength;
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
        ByteArrayOutputStream endWriter =
                new ByteArrayOutputStream();

        Part.sendMessageEnd(endWriter, boundary);

        overallLength += writeToTarget(target, endWriter);

        startPart = tempPart;

        return overallLength;
    }

    private long handleClientPart(
            WritableByteChannel target, com.ning.http.client.Part part) throws IOException {

        if (part.getClass().equals(com.ning.http.client.StringPart.class)) {
            com.ning.http.client.StringPart stringPart = (com.ning.http.client.StringPart) part;

            StringPart currentPart = new StringPart(stringPart.getName(), stringPart.getValue());

            return handleStringPart(target, currentPart);
        } else if (part.getClass().equals(com.ning.http.client.FilePart.class)) {
            com.ning.http.client.FilePart currentPart = (com.ning.http.client.FilePart) part;

            FilePart filePart = new FilePart(currentPart.getName(), currentPart.getFile());

            return handleFilePart(target, filePart);
        } else if (part.getClass().equals(com.ning.http.client.ByteArrayPart.class)) {
            com.ning.http.client.ByteArrayPart bytePart = (com.ning.http.client.ByteArrayPart) part;

            ByteArrayPartSource source = new ByteArrayPartSource(bytePart.getFileName(), bytePart.getData());

            FilePart filePart = new FilePart(bytePart.getName(), source, bytePart.getMimeType(), bytePart.getCharSet());

            return handleByteArrayPart(target, filePart, bytePart.getData());
        }

        return 0;
    }

    private long handleByteArrayPart(WritableByteChannel target,
                                     FilePart filePart, byte[] data) throws IOException {

        int length = 0;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Part.sendPart(output, filePart, boundary);
        length += writeToTarget(target, output);
        return length;

    }

    private long handleFileEnd(WritableByteChannel target, FilePart filePart)
            throws IOException {

        ByteArrayOutputStream endOverhead = new ByteArrayOutputStream();

        filePart.sendEnd(endOverhead);

        return this.writeToTarget(target, endOverhead);
    }

    private long handleFileHeaders(WritableByteChannel target, FilePart filePart) throws IOException {
        filePart.setPartBoundary(boundary);

        ByteArrayOutputStream overhead = new ByteArrayOutputStream();

        filePart.setPartBoundary(boundary);

        filePart.sendStart(overhead);
        filePart.sendDispositionHeader(overhead);
        filePart.sendContentTypeHeader(overhead);
        filePart.sendTransferEncodingHeader(overhead);
        filePart.sendEndOfHeader(overhead);

        return writeToTarget(target, overhead);
    }

    private long handleFilePart(WritableByteChannel target, FilePart filePart) throws IOException {

        if ( FilePartSource.class.isAssignableFrom( filePart.getSource().getClass())) {
            int length = 0;

            length += handleFileHeaders(target, filePart);
            FilePartSource source = (FilePartSource) filePart.getSource();

            File file = source.getFile();

            RandomAccessFile raf = new RandomAccessFile(file, "r");
            files.add(raf);

            FileChannel fc = raf.getChannel();

            long fileLength = fc.transferTo(0, file.length(), target);

            if (fileLength != file.length()) {
                logger.info("Did not complete file.");
            }

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

        int nRead = 0;
        byte[] bytes = new byte[(int)partSource.getLength()];
        while (nRead != -1) {
            nRead = stream.read(bytes);
            if (nRead > 0) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(nRead);
                bos.write(bytes, 0, nRead);
                writeToTarget(target, bos);
            }
        }
        length += handleFileEnd(target, filePart);

        return length;
    }

    private long handleStringPart(WritableByteChannel target, StringPart currentPart) throws IOException {

        currentPart.setPartBoundary(boundary);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        Part.sendPart(outputStream, currentPart, boundary);

        return writeToTarget(target, outputStream);
    }

    private long handleMultiPart(WritableByteChannel target, Part currentPart) throws IOException {

        currentPart.setPartBoundary(boundary);

        if (currentPart.getClass().equals(StringPart.class)) {
            return handleStringPart(target, (StringPart) currentPart);
        } else if (currentPart.getClass().equals(FilePart.class)) {
            FilePart filePart = (FilePart) currentPart;

            return handleFilePart(target, filePart);
        }
        return 0;
    }

    private long writeToTarget(WritableByteChannel target, ByteArrayOutputStream byteWriter)
            throws IOException {

        int written = 0;
        synchronized (byteWriter) {
            while ((target.isOpen()) && (written < byteWriter.size())) {
                ByteBuffer message = ByteBuffer.wrap(byteWriter.toByteArray());
                written = target.write(message);
                // TODO: This is dangerous to spin
                if (written != byteWriter.size()) {
                    logger.info("Waiting for writing...");
                    try {
                        byteWriter.wait(1000);
                    } catch (InterruptedException e) {
                        logger.trace(e.getMessage(), e);
                    }
                }
            }
        }
        return written;
    }

}
