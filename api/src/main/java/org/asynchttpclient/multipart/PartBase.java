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

public abstract class PartBase extends Part {

    /**
     * Name of the file part.
     */
    private final String name;

    /**
     * Content type of the file part.
     */
    private final String contentType;

    /**
     * Content encoding of the file part.
     */
    private final String charSet;

    /**
     * The transfer encoding.
     */
    private final String transferEncoding;

    private final String contentId;

    /**
     * Constructor.
     * 
     * @param name The name of the part
     * @param contentType The content type, or <code>null</code>
     * @param charSet The character encoding, or <code>null</code>
     * @param transferEncoding The transfer encoding, or <code>null</code>
     * @param contentId The content id, or <code>null</code>
     */
    public PartBase(String name, String contentType, String charSet, String transferEncoding, String contentId) {

        if (name == null) {
            throw new NullPointerException("name");
        }
        this.name = name;
        this.contentType = contentType;
        this.charSet = charSet;
        this.transferEncoding = transferEncoding;
        this.contentId = contentId;
    }

    /**
     * Returns the name.
     * 
     * @return The name.
     */
    public String getName() {
        return this.name;
    }

    /**
     * Returns the content type of this part.
     * 
     * @return String The name.
     */
    public String getContentType() {
        return this.contentType;
    }

    /**
     * Return the character encoding of this part.
     * 
     * @return String The name.
     */
    public String getCharSet() {
        return this.charSet;
    }

    /**
     * Returns the transfer encoding of this part.
     * 
     * @return String The name.
     */
    public String getTransferEncoding() {
        return transferEncoding;
    }

    public String getContentId() {
        return contentId;
    }
}
