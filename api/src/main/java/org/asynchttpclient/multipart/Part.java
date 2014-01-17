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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.WritableByteChannel;

import org.asynchttpclient.util.StandardCharsets;

public abstract class Part {

    /**
     * Carriage return/linefeed as a byte array
     */
    public static final byte[] CRLF_BYTES = "\r\n".getBytes(StandardCharsets.US_ASCII);

    /**
     * Content dispostion as a byte array
     */
    public static final byte[] QUOTE_BYTES = "\"".getBytes(StandardCharsets.US_ASCII);

    /**
     * Extra characters as a byte array
     */
    public static final byte[] EXTRA_BYTES = "--".getBytes(StandardCharsets.US_ASCII);

    /**
     * Content dispostion as a byte array
     */
    public static final byte[] CONTENT_DISPOSITION_BYTES = "Content-Disposition: form-data; name=".getBytes(StandardCharsets.US_ASCII);

    /**
     * Content type header as a byte array
     */
    public static final byte[] CONTENT_TYPE_BYTES = "Content-Type: ".getBytes(StandardCharsets.US_ASCII);

    /**
     * Content charset as a byte array
     */
    public static final byte[] CHARSET_BYTES = "; charset=".getBytes(StandardCharsets.US_ASCII);

    /**
     * Content type header as a byte array
     */
    public static final byte[] CONTENT_TRANSFER_ENCODING_BYTES = "Content-Transfer-Encoding: ".getBytes(StandardCharsets.US_ASCII);

    /**
     * Content type header as a byte array
     */
    public static final byte[] CONTENT_ID_BYTES = "Content-ID: ".getBytes(StandardCharsets.US_ASCII);

    /**
     * Return the name of this part.
     * 
     * @return The name.
     */
    public abstract String getName();

    /**
     * Returns the content type of this part.
     * 
     * @return the content type, or <code>null</code> to exclude the content type header
     */
    public abstract String getContentType();

    /**
     * Return the character encoding of this part.
     * 
     * @return the character encoding, or <code>null</code> to exclude the character encoding header
     */
    public abstract String getCharSet();

    /**
     * Return the transfer encoding of this part.
     * 
     * @return the transfer encoding, or <code>null</code> to exclude the transfer encoding header
     */
    public abstract String getTransferEncoding();

    /**
     * Return the content ID of this part.
     * 
     * @return the content ID, or <code>null</code> to exclude the content ID header
     */
    public abstract String getContentId();

    /**
     * Tests if this part can be sent more than once.
     * 
     * @return <code>true</code> if {@link #sendData(java.io.OutputStream)} can be successfully called more than once.
     * @since 3.0
     */
    public boolean isRepeatable() {
        return true;
    }

    protected void visitStart(PartVisitor visitor, byte[] boundary) throws IOException {
        visitor.withBytes(EXTRA_BYTES);
        visitor.withBytes(boundary);
    }

    protected void visitDispositionHeader(PartVisitor visitor) throws IOException {
        if (getName() != null) {
            visitor.withBytes(CRLF_BYTES);
            visitor.withBytes(CONTENT_DISPOSITION_BYTES);
            visitor.withBytes(QUOTE_BYTES);
            visitor.withBytes(getName().getBytes(StandardCharsets.US_ASCII));
            visitor.withBytes(QUOTE_BYTES);
        }
    }

    protected void visitContentTypeHeader(PartVisitor visitor) throws IOException {
        String contentType = getContentType();
        if (contentType != null) {
            visitor.withBytes(CRLF_BYTES);
            visitor.withBytes(CONTENT_TYPE_BYTES);
            visitor.withBytes(contentType.getBytes(StandardCharsets.US_ASCII));
            String charSet = getCharSet();
            if (charSet != null) {
                visitor.withBytes(CHARSET_BYTES);
                visitor.withBytes(charSet.getBytes(StandardCharsets.US_ASCII));
            }
        }
    }

    protected void visitTransferEncodingHeader(PartVisitor visitor) throws IOException {
        String transferEncoding = getTransferEncoding();
        if (transferEncoding != null) {
            visitor.withBytes(CRLF_BYTES);
            visitor.withBytes(CONTENT_TRANSFER_ENCODING_BYTES);
            visitor.withBytes(transferEncoding.getBytes(StandardCharsets.US_ASCII));
        }
    }

    protected void visitContentIdHeader(PartVisitor visitor) throws IOException {
        String contentId = getContentId();
        if (contentId != null) {
            visitor.withBytes(CRLF_BYTES);
            visitor.withBytes(CONTENT_ID_BYTES);
            visitor.withBytes(contentId.getBytes(StandardCharsets.US_ASCII));
        }
    }

    protected void visitEndOfHeader(PartVisitor visitor) throws IOException {
        visitor.withBytes(CRLF_BYTES);
        visitor.withBytes(CRLF_BYTES);
    }

    protected void visitEnd(PartVisitor visitor) throws IOException {
        visitor.withBytes(CRLF_BYTES);
    }

    protected abstract long getDataLength();

    protected abstract void sendData(OutputStream out) throws IOException;

    /**
     * Write all the data to the output stream. If you override this method make sure to override #length() as well
     * 
     * @param out
     *            The output stream
     * @param boundary
     *            the boundary
     * @throws IOException
     *             If an IO problem occurs.
     */
    public void write(OutputStream out, byte[] boundary) throws IOException {

        OutputStreamPartVisitor visitor = new OutputStreamPartVisitor(out);

        visitStart(visitor, boundary);
        visitDispositionHeader(visitor);
        visitContentTypeHeader(visitor);
        visitTransferEncodingHeader(visitor);
        visitContentIdHeader(visitor);
        visitEndOfHeader(visitor);
        sendData(visitor.getOutputStream());
        visitEnd(visitor);
    }

    /**
     * Return the full length of all the data. If you override this method make sure to override #send(OutputStream) as well
     * 
     * @return long The length.
     */
    public long length(byte[] boundary) {

        long dataLength = getDataLength();
        try {

            if (dataLength < 0L) {
                return -1L;
            } else {
                CounterPartVisitor visitor = new CounterPartVisitor();
                visitStart(visitor, boundary);
                visitDispositionHeader(visitor);
                visitContentTypeHeader(visitor);
                visitTransferEncodingHeader(visitor);
                visitContentIdHeader(visitor);
                visitEndOfHeader(visitor);
                visitEnd(visitor);
                return dataLength + visitor.getCount();
            }
        } catch (IOException e) {
            // can't happen
            throw new RuntimeException("IOException while computing length, WTF", e);
        }
    }

    /**
     * Return a string representation of this object.
     * 
     * @return A string representation of this object.
     * @see java.lang.Object#toString()
     */
    public String toString() {
        return this.getName();
    }

    public abstract long write(WritableByteChannel target, byte[] boundary) throws IOException;
}
