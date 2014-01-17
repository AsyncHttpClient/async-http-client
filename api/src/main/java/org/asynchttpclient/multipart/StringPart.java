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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;

public class StringPart extends PartBase {

    /**
     * Default content encoding of string parameters.
     */
    public static final String DEFAULT_CONTENT_TYPE = "text/plain";

    /**
     * Default charset of string parameters
     */
    public static final String DEFAULT_CHARSET = "US-ASCII";

    /**
     * Default transfer encoding of string parameters
     */
    public static final String DEFAULT_TRANSFER_ENCODING = "8bit";

    /**
     * Contents of this StringPart.
     */
    private final byte[] content;

    public StringPart(String name, String value, String charset) {
        this(name, value, charset, null);
    }

    /**
     * Constructor.
     * 
     * @param name
     *            The name of the part
     * @param value
     *            the string to post
     * @param charset
     *            the charset to be used to encode the string, if <code>null</code> the {@link #DEFAULT_CHARSET default} is used
     * @param contentId
     *            the content id
     */
    public StringPart(String name, String value, String charset, String contentId) {

        super(name, DEFAULT_CONTENT_TYPE, charset == null ? DEFAULT_CHARSET : charset, DEFAULT_TRANSFER_ENCODING, contentId);
        if (value == null) {
            throw new IllegalArgumentException("Value may not be null");
        }
        if (value.indexOf(0) != -1) {
            // See RFC 2048, 2.8. "8bit Data"
            throw new IllegalArgumentException("NULs may not be present in string parts");
        }
        content = value.getBytes(Charset.forName(charset));
    }

    /**
     * Writes the data to the given OutputStream.
     * 
     * @param out
     *            the OutputStream to write to
     * @throws java.io.IOException
     *             if there is a write error
     */
    protected void sendData(OutputStream out) throws IOException {
        out.write(content);
    }

    /**
     * Return the length of the data.
     * 
     * @return The length of the data.
     */
    protected long getDataLength() {
        return content.length;
    }
    
    public byte[] getBytes(byte[] boundary) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        write(outputStream, boundary);
        return outputStream.toByteArray();
    }

    @Override
    public long write(WritableByteChannel target, byte[] boundary) throws IOException {
        return MultipartUtils.writeBytesToChannel(target, getBytes(boundary));
    }
}
