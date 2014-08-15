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

import static org.asynchttpclient.util.StandardCharsets.US_ASCII;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;

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
     * Default transfer encoding of file attachments.
     */
    public static final String DEFAULT_TRANSFER_ENCODING = "binary";

    /**
     * Attachment's file name as a byte array
     */
    private static final byte[] FILE_NAME_BYTES = "; filename=".getBytes(US_ASCII);

    private long stalledTime = -1L;

    private String fileName;

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
     *            the charset encoding for this part
     */
    public AbstractFilePart(String name, String contentType, Charset charset, String contentId, String transfertEncoding) {
        super(name,//
                contentType == null ? DEFAULT_CONTENT_TYPE : contentType,//
                charset,//
                contentId,//
                transfertEncoding == null ? DEFAULT_TRANSFER_ENCODING : transfertEncoding);
    }

    protected void visitDispositionHeader(PartVisitor visitor) throws IOException {
        super.visitDispositionHeader(visitor);
        if (fileName != null) {
            visitor.withBytes(FILE_NAME_BYTES);
            visitor.withByte(QUOTE_BYTE);
            visitor.withBytes(fileName.getBytes(US_ASCII));
            visitor.withByte(QUOTE_BYTE);
        }
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

    public void setStalledTime(long ms) {
        stalledTime = ms;
    }

    public long getStalledTime() {
        return stalledTime;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileName() {
        return fileName;
    }

    @Override
    public String toString() {
        return new StringBuilder()//
                .append(super.toString())//
                .append(" filename=").append(fileName)//
                .toString();
    }
}
