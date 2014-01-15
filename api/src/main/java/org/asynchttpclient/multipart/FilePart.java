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

public class FilePart extends AbstractFilePart {

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
        if (lengthOfData() == 0) {

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
    protected long lengthOfData() {
        return file.length();
    }

    public File getFile() {
        return file;
    }
}
