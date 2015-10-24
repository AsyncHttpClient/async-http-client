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

import static org.asynchttpclient.util.Assertions.*;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
    public static final Charset DEFAULT_CHARSET = US_ASCII;

    /**
     * Default transfer encoding of string parameters
     */
    public static final String DEFAULT_TRANSFER_ENCODING = "8bit";

    /**
     * Contents of this StringPart.
     */
    private final byte[] content;
    private final String value;

    private static Charset charsetOrDefault(Charset charset) {
        return charset == null ? DEFAULT_CHARSET : charset;
    }

    private static String contentTypeOrDefault(String contentType) {
        return contentType == null ? DEFAULT_CONTENT_TYPE : contentType;
    }

    private static String transferEncodingOrDefault(String transferEncoding) {
        return transferEncoding == null ? DEFAULT_TRANSFER_ENCODING : transferEncoding;
    }

    public StringPart(String name, String value) {
        this(name, value, null);
    }

    public StringPart(String name, String value, String contentType) {
        this(name, value, contentType, null);
    }

    public StringPart(String name, String value, String contentType, Charset charset) {
        this(name, value, contentType, charset, null);
    }

    public StringPart(String name, String value, String contentType, Charset charset, String contentId) {
        this(name, value, contentType, charset, contentId, null);
    }

    public StringPart(String name, String value, String contentType, Charset charset, String contentId, String transferEncoding) {
        super(name, contentTypeOrDefault(contentType), charsetOrDefault(charset), contentId, transferEncodingOrDefault(transferEncoding));
        assertNotNull(value, "value");

        if (value.indexOf(0) != -1)
            // See RFC 2048, 2.8. "8bit Data"
            throw new IllegalArgumentException("NULs may not be present in string parts");

        content = value.getBytes(getCharset());
        this.value = value;
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
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        OutputStreamPartVisitor visitor = new OutputStreamPartVisitor(os);
        visitStart(visitor, boundary);
        visitDispositionHeader(visitor);
        visitContentTypeHeader(visitor);
        visitTransferEncodingHeader(visitor);
        visitContentIdHeader(visitor);
        visitCustomHeaders(visitor);
        visitEndOfHeaders(visitor);
        os.write(content);
        visitEnd(visitor);
        return os.toByteArray();
    }

    @Override
    public long write(WritableByteChannel target, byte[] boundary) throws IOException {
        return MultipartUtils.writeBytesToChannel(target, getBytes(boundary));
    }

    public String getValue() {
        return value;
    }
}
