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

import org.asynchttpclient.util.StandardCharsets;

/**
 * This class is an adaptation of the Apache HttpClient implementation
 * 
 * @link http://hc.apache.org/httpclient-3.x/
 */
public abstract class AbstractFilePart extends PartBase {

    /**
     * Default content encoding of file attachments.
     */
    public static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    /**
     * Default charset of file attachments.
     */
    public static final String DEFAULT_CHARSET = StandardCharsets.ISO_8859_1.name();

    /**
     * Default transfer encoding of file attachments.
     */
    public static final String DEFAULT_TRANSFER_ENCODING = "binary";

    /**
     * Attachment's file name as a byte array
     */
    private static final byte[] FILE_NAME_BYTES = "; filename=".getBytes(StandardCharsets.US_ASCII);

    private long stalledTime = -1L;

    /**
     * FilePart Constructor.
     * 
     * @param name
     *            the name for this part
     * @param partSource
     *            the source for this part
     * @param contentType
     *            the content type for this part, if <code>null</code> the {@link #DEFAULT_CONTENT_TYPE default} is used
     * @param charset
     *            the charset encoding for this part, if <code>null</code> the {@link #DEFAULT_CHARSET default} is used
     * @param contentId
     */
    public AbstractFilePart(String name, String contentType, String charset, String contentId) {
        super(name, contentType == null ? DEFAULT_CONTENT_TYPE : contentType, charset == null ? DEFAULT_CHARSET : charset, DEFAULT_TRANSFER_ENCODING, contentId);
    }

    public abstract String getFileName();

    protected void visitDispositionHeader(PartVisitor visitor) throws IOException {
        super.visitDispositionHeader(visitor);
        String filename = getFileName();
        if (filename != null) {
            visitor.withBytes(FILE_NAME_BYTES);
            visitor.withBytes(QUOTE_BYTES);
            visitor.withBytes(filename.getBytes(StandardCharsets.US_ASCII));
            visitor.withBytes(QUOTE_BYTES);
        }
    }

    public void setStalledTime(long ms) {
        stalledTime = ms;
    }

    public long getStalledTime() {
        return stalledTime;
    }

    protected byte[] generateFileStart(byte[] boundary) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OutputStreamPartVisitor visitor = new OutputStreamPartVisitor(out);
        visitStart(visitor, boundary);
        visitDispositionHeader(visitor);
        visitContentTypeHeader(visitor);
        visitTransferEncodingHeader(visitor);
        visitContentIdHeader(visitor);
        visitEndOfHeader(visitor);

        return out.toByteArray();
    }

    protected byte[] generateFileEnd() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OutputStreamPartVisitor visitor = new OutputStreamPartVisitor(out);
        visitEnd(visitor);
        return out.toByteArray();
    }
}
