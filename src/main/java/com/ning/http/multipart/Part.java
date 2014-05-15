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
package com.ning.http.multipart;

import java.io.IOException;
import java.io.OutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is an adaptation of the Apache HttpClient implementation
 * 
 * @link http://hc.apache.org/httpclient-3.x/
 */
public abstract class Part implements com.ning.http.client.Part {

    private static final Logger LOGGER = LoggerFactory.getLogger(Part.class);

    /**
     * Carriage return/linefeed
     */
    public static final String CRLF = "\r\n";

    /**
     * Carriage return/linefeed as a byte array
     */
    public static final byte[] CRLF_BYTES = MultipartEncodingUtil.getAsciiBytes(CRLF);

    /**
     * Content dispostion characters
     */
    public static final String QUOTE = "\"";

    /**
     * Content dispostion as a byte array
     */
    public static final byte[] QUOTE_BYTES = MultipartEncodingUtil.getAsciiBytes(QUOTE);

    /**
     * Extra characters
     */
    public static final String EXTRA = "--";

    /**
     * Extra characters as a byte array
     */
    public static final byte[] EXTRA_BYTES = MultipartEncodingUtil.getAsciiBytes(EXTRA);

    /**
     * Content disposition characters
     */
    public static final String CONTENT_DISPOSITION = "Content-Disposition: ";

    /**
     * Content disposition as a byte array
     */
    public static final byte[] CONTENT_DISPOSITION_BYTES = MultipartEncodingUtil.getAsciiBytes(CONTENT_DISPOSITION);

    /**
     * form-data characters
     */
    public static final String FORM_DATA_DISPOSITION_TYPE = "form-data";

    /**
     * form-data as a byte array
     */
    public static final byte[] FORM_DATA_DISPOSITION_TYPE_BYTES = MultipartEncodingUtil.getAsciiBytes(FORM_DATA_DISPOSITION_TYPE);

    /**
     * name characters
     */
    public static final String NAME = "; name=";

    /**
     * name as a byte array
     */
    public static final byte[] NAME_BYTES = MultipartEncodingUtil.getAsciiBytes(NAME);

    /**
     * Content type header
     */
    public static final String CONTENT_TYPE = "Content-Type: ";

    /**
     * Content type header as a byte array
     */
    public static final byte[] CONTENT_TYPE_BYTES = MultipartEncodingUtil.getAsciiBytes(CONTENT_TYPE);

    /**
     * Content charset
     */
    public static final String CHARSET = "; charset=";

    /**
     * Content charset as a byte array
     */
    public static final byte[] CHARSET_BYTES = MultipartEncodingUtil.getAsciiBytes(CHARSET);

    /**
     * Content type header
     */
    public static final String CONTENT_TRANSFER_ENCODING = "Content-Transfer-Encoding: ";

    /**
     * Content type header as a byte array
     */
    public static final byte[] CONTENT_TRANSFER_ENCODING_BYTES = MultipartEncodingUtil.getAsciiBytes(CONTENT_TRANSFER_ENCODING);

    /**
     * Content type header
     */
    public static final String CONTENT_ID = "Content-ID: ";

    /**
     * Content type header as a byte array
     */
    public static final byte[] CONTENT_ID_BYTES = MultipartEncodingUtil.getAsciiBytes(CONTENT_ID);

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

    private String dispositionType;

    /**
     * Gets the disposition-type to be used in Content-Disposition header
     * 
     * @return the disposition-type
     */
    public String getDispositionType() {
        return dispositionType;
    }

    public void setDispositionType(String dispositionType) {
        this.dispositionType = dispositionType;
    }

    /**
     * Write the start to the specified output stream
     * 
     * @param out The output stream
     * @param boundary the boundary
     * @throws java.io.IOException If an IO problem occurs.
     */
    protected void sendStart(OutputStream out, byte[] boundary) throws IOException {
        out.write(EXTRA_BYTES);
        out.write(boundary);
    }

    private int startLength(byte[] boundary) {
        return EXTRA_BYTES.length + boundary.length;
    }

    /**
     * Write the content disposition header to the specified output stream
     * 
     * @param out The output stream
     * @throws IOException If an IO problem occurs.
     */
    protected void sendDispositionHeader(OutputStream out) throws IOException {
        out.write(CRLF_BYTES);
        out.write(CONTENT_DISPOSITION_BYTES);
        if (dispositionType != null)
            out.write(MultipartEncodingUtil.getAsciiBytes(dispositionType));
        else
            out.write(FORM_DATA_DISPOSITION_TYPE_BYTES);

        if (getName() != null) {
            out.write(NAME_BYTES);
            out.write(QUOTE_BYTES);
            out.write(MultipartEncodingUtil.getAsciiBytes(getName()));
            out.write(QUOTE_BYTES);
        }
    }

    protected long dispositionHeaderLength() {
        long length = 0L;

        length += CRLF_BYTES.length;
        length += CONTENT_DISPOSITION_BYTES.length;
        if (dispositionType != null)
            length += MultipartEncodingUtil.getAsciiBytes(dispositionType).length;
        else
            length += FORM_DATA_DISPOSITION_TYPE_BYTES.length;

        if (getName() != null) {
            length += NAME_BYTES.length;
            length += QUOTE_BYTES.length;
            length += MultipartEncodingUtil.getAsciiBytes(getName()).length;
            length += QUOTE_BYTES.length;
        }
        return length;
    }

    /**
     * Write the content type header to the specified output stream
     * 
     * @param out The output stream
     * @throws IOException If an IO problem occurs.
     */
    protected void sendContentTypeHeader(OutputStream out) throws IOException {
        String contentType = getContentType();
        if (contentType != null) {
            out.write(CRLF_BYTES);
            out.write(CONTENT_TYPE_BYTES);
            out.write(MultipartEncodingUtil.getAsciiBytes(contentType));
            String charSet = getCharSet();
            if (charSet != null) {
                out.write(CHARSET_BYTES);
                out.write(MultipartEncodingUtil.getAsciiBytes(charSet));
            }
        }
    }

    protected long contentTypeHeaderLength() {
        long length = 0L;
        String contentType = getContentType();
        if (contentType != null) {
            length += CRLF_BYTES.length;
            length += CONTENT_TYPE_BYTES.length;
            length += MultipartEncodingUtil.getAsciiBytes(contentType).length;
            String charSet = getCharSet();
            if (charSet != null) {
                length += CHARSET_BYTES.length;
                length += MultipartEncodingUtil.getAsciiBytes(charSet).length;
            }
        }
        return length;
    }

    /**
     * Write the content transfer encoding header to the specified output stream
     * 
     * @param out The output stream
     * @throws IOException If an IO problem occurs.
     */
    protected void sendTransferEncodingHeader(OutputStream out) throws IOException {
        String transferEncoding = getTransferEncoding();
        if (transferEncoding != null) {
            out.write(CRLF_BYTES);
            out.write(CONTENT_TRANSFER_ENCODING_BYTES);
            out.write(MultipartEncodingUtil.getAsciiBytes(transferEncoding));
        }
    }

    protected long transferEncodingHeaderLength() {
        long length = 0L;
        String transferEncoding = getTransferEncoding();
        if (transferEncoding != null) {
            length += CRLF_BYTES.length;
            length += CONTENT_TRANSFER_ENCODING_BYTES.length;
            length += MultipartEncodingUtil.getAsciiBytes(transferEncoding).length;
        }
        return length;
    }

    /**
     * Write the content ID header to the specified output stream
     * 
     * @param out The output stream
     * @throws IOException If an IO problem occurs.
     */
    protected void sendContentIdHeader(OutputStream out) throws IOException {
        String contentId = getContentId();
        if (contentId != null) {
            out.write(CRLF_BYTES);
            out.write(CONTENT_ID_BYTES);
            out.write(MultipartEncodingUtil.getAsciiBytes(contentId));
        }
    }

    protected long contentIdHeaderLength() {
        long length = 0L;
        String contentId = getContentId();
        if (contentId != null) {
            length += CRLF_BYTES.length;
            length += CONTENT_ID_BYTES.length;
            length += MultipartEncodingUtil.getAsciiBytes(contentId).length;
        }
        return length;
    }

    /**
     * Write the end of the header to the output stream
     * 
     * @param out The output stream
     * @throws IOException If an IO problem occurs.
     */
    protected void sendEndOfHeader(OutputStream out) throws IOException {
        out.write(CRLF_BYTES);
        out.write(CRLF_BYTES);
    }

    protected long endOfHeaderLength() {
        return CRLF_BYTES.length * 2;
    }

    /**
     * Write the data to the specified output stream
     * 
     * @param out The output stream
     * @throws IOException If an IO problem occurs.
     */
    protected abstract void sendData(OutputStream out) throws IOException;

    /**
     * Return the length of the main content
     * 
     * @return long The length.
     */
    protected abstract long lengthOfData();

    /**
     * Write the end data to the output stream.
     * 
     * @param out The output stream
     * @throws IOException If an IO problem occurs.
     */
    protected void sendEnd(OutputStream out) throws IOException {
        out.write(CRLF_BYTES);
    }

    protected long endLength() {
        return CRLF_BYTES.length;
    }

    /**
     * Write all the data to the output stream. If you override this method make sure to override #length() as well
     * 
     * @param out The output stream
     * @param boundary the boundary
     * @throws IOException If an IO problem occurs.
     */
    public void send(OutputStream out, byte[] boundary) throws IOException {
        sendStart(out, boundary);
        sendDispositionHeader(out);
        sendContentTypeHeader(out);
        sendTransferEncodingHeader(out);
        sendContentIdHeader(out);
        sendEndOfHeader(out);
        sendData(out);
        sendEnd(out);
    }

    /**
     * Return the full length of all the data. If you override this method make sure to override #send(OutputStream) as well
     * 
     * @return long The length.
     * @throws IOException If an IO problem occurs
     */
    public long length(byte[] boundary) {

        long lengthOfData = lengthOfData();

        if (lengthOfData < 0L) {
            return -1L;
        } else {
            return lengthOfData//
                    + startLength(boundary)//
                    + dispositionHeaderLength()//
                    + contentTypeHeaderLength()//
                    + transferEncodingHeaderLength()//
                    + contentIdHeaderLength()//
                    + endOfHeaderLength()//
                    + endLength();
        }
    }

    /**
     * Return a string representation of this object.
     * 
     * @return A string representation of this object.
     * @see java.lang.Object#toString()
     */
    public String toString() {
        return new StringBuilder()//
                .append("name=").append(getName())//
                .append(" contentType=").append(getContentType())//
                .append(" charset=").append(getCharSet())//
                .append(" tranferEncoding=").append(getTransferEncoding())//
                .append(" contentId=").append(getContentId())//
                .append(" dispositionType=").append(getDispositionType())//
                .toString();
    }

    /**
     * Write all parts and the last boundary to the specified output stream.
     * 
     * @param out The stream to write to.
     * @param parts The parts to write.
     * @param partBoundary The ASCII bytes to use as the part boundary.
     * @throws IOException If an I/O error occurs while writing the parts.
     * @since 3.0
     */
    public static void sendParts(OutputStream out, Part[] parts, byte[] partBoundary) throws IOException {

        if (parts == null) {
            throw new IllegalArgumentException("Parts may not be null");
        }
        if (partBoundary == null || partBoundary.length == 0) {
            throw new IllegalArgumentException("partBoundary may not be empty");
        }
        for (Part part : parts) {
            part.send(out, partBoundary);
        }
        out.write(EXTRA_BYTES);
        out.write(partBoundary);
        out.write(EXTRA_BYTES);
        out.write(CRLF_BYTES);
    }

    public static void sendMessageEnd(OutputStream out, byte[] partBoundary) throws IOException {

        if (partBoundary == null || partBoundary.length == 0) {
            throw new IllegalArgumentException("partBoundary may not be empty");
        }

        out.write(EXTRA_BYTES);
        out.write(partBoundary);
        out.write(EXTRA_BYTES);
        out.write(CRLF_BYTES);
    }

    /**
     * Write all parts and the last boundary to the specified output stream.
     * 
     * @param out The stream to write to.
     * @param part The part to write.
     * @throws IOException If an I/O error occurs while writing the parts.
     * @since N/A
     */
    public static void sendPart(OutputStream out, Part part, byte[] partBoundary) throws IOException {

        if (part == null) {
            throw new IllegalArgumentException("Parts may not be null");
        }

        part.send(out, partBoundary);
    }

    /**
     * Gets the length of the multipart message including the given parts.
     * 
     * @param parts The parts.
     * @param partBoundary The ASCII bytes to use as the part boundary.
     * @return The total length
     * @throws IOException If an I/O error occurs while writing the parts.
     * @since 3.0
     */
    public static long getLengthOfParts(Part[] parts, byte[] partBoundary) {

        try {
            if (parts == null) {
                throw new IllegalArgumentException("Parts may not be null");
            }
            long total = 0;
            for (Part part : parts) {
                long l = part.length(partBoundary);
                if (l < 0) {
                    return -1;
                }
                total += l;
            }
            total += EXTRA_BYTES.length;
            total += partBoundary.length;
            total += EXTRA_BYTES.length;
            total += CRLF_BYTES.length;
            return total;
        } catch (Exception e) {
            LOGGER.error("An exception occurred while getting the length of the parts", e);
            return 0L;
        }
    }
}
