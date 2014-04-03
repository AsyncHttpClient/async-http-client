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

public abstract class PartBase extends Part {

    /**
     * The name of the form field, part of the Content-Disposition header
     */
    private final String name;

    /**
     * The main part of the Content-Type header
     */
    private final String contentType;

    /**
     * The charset (part of Content-Type header)
     */
    private final String charSet;

    /**
     * The Content-Transfer-Encoding header value.
     */
    private final String transferEncoding;

    /**
     * The Content-Id
     */
    private final String contentId;

    /**
     * The disposition type (part of Content-Disposition)
     */
    private String dispositionType;

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

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public String getContentType() {
        return this.contentType;
    }

    @Override
    public String getCharSet() {
        return this.charSet;
    }

    @Override
    public String getTransferEncoding() {
        return transferEncoding;
    }

    @Override
    public String getContentId() {
        return contentId;
    }

    @Override
    public String getDispositionType() {
        return dispositionType;
    }

    public void setDispositionType(String dispositionType) {
        this.dispositionType = dispositionType;
    }
}
