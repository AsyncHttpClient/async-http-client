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

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.nio.charset.Charset;
import java.util.List;

import org.asynchttpclient.Param;

public interface Part {

    /**
     * Carriage return/linefeed as a byte array
     */
    byte[] CRLF_BYTES = "\r\n".getBytes(US_ASCII);

    /**
     * Content dispostion as a byte
     */
    byte QUOTE_BYTE = '\"';

    /**
     * Extra characters as a byte array
     */
    byte[] EXTRA_BYTES = "--".getBytes(US_ASCII);

    /**
     * Content dispostion as a byte array
     */
    byte[] CONTENT_DISPOSITION_BYTES = "Content-Disposition: ".getBytes(US_ASCII);

    /**
     * form-data as a byte array
     */
    byte[] FORM_DATA_DISPOSITION_TYPE_BYTES = "form-data".getBytes(US_ASCII);

    /**
     * name as a byte array
     */
    byte[] NAME_BYTES = "; name=".getBytes(US_ASCII);

    /**
     * Content type header as a byte array
     */
    byte[] CONTENT_TYPE_BYTES = "Content-Type: ".getBytes(US_ASCII);

    /**
     * Content charset as a byte array
     */
    byte[] CHARSET_BYTES = "; charset=".getBytes(US_ASCII);

    /**
     * Content type header as a byte array
     */
    byte[] CONTENT_TRANSFER_ENCODING_BYTES = "Content-Transfer-Encoding: ".getBytes(US_ASCII);

    /**
     * Content type header as a byte array
     */
    byte[] CONTENT_ID_BYTES = "Content-ID: ".getBytes(US_ASCII);

    /**
     * Return the name of this part.
     * 
     * @return The name.
     */
    String getName();

    /**
     * Returns the content type of this part.
     * 
     * @return the content type, or <code>null</code> to exclude the content
     *         type header
     */
    String getContentType();

    /**
     * Return the character encoding of this part.
     * 
     * @return the character encoding, or <code>null</code> to exclude the
     *         character encoding header
     */
    Charset getCharset();

    /**
     * Return the transfer encoding of this part.
     * 
     * @return the transfer encoding, or <code>null</code> to exclude the
     *         transfer encoding header
     */
    String getTransferEncoding();

    /**
     * Return the content ID of this part.
     * 
     * @return the content ID, or <code>null</code> to exclude the content ID
     *         header
     */
    String getContentId();

    /**
     * Gets the disposition-type to be used in Content-Disposition header
     * 
     * @return the disposition-type
     */
    String getDispositionType();

    List<Param> getCustomHeaders();
}
