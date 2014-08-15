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
package org.asynchttpclient.multipart;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;

import org.asynchttpclient.util.StandardCharsets;

public class StringPart extends PartBase {

    /**
     * Default content encoding of string parameters.
     */
    public static final String DEFAULT_CONTENT_TYPE = "text/plain";

    /**
     * Default charset of string parameters
     */
    public static final Charset DEFAULT_CHARSET = StandardCharsets.US_ASCII;

    /**
     * Default transfer encoding of string parameters
     */
    public static final String DEFAULT_TRANSFER_ENCODING = "8bit";

    /**
     * Contents of this StringPart.
     */
    private final byte[] content;

    private static Charset charsetOrDefault(Charset charset) {
        return charset == null ? DEFAULT_CHARSET : charset;
    }

    public StringPart(String name, String value, Charset charset) {
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
    public StringPart(String name, String value, Charset charset, String contentId) {

        super(name, DEFAULT_CONTENT_TYPE, charsetOrDefault(charset), DEFAULT_TRANSFER_ENCODING, contentId);
        if (value == null)
            throw new NullPointerException("value");
        if (value.indexOf(0) != -1)
            // See RFC 2048, 2.8. "8bit Data"
            throw new IllegalArgumentException("NULs may not be present in string parts");
        content = value.getBytes(charsetOrDefault(charset));
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
