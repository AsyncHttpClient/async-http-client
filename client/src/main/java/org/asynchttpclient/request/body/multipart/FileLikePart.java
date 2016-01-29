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

import static org.asynchttpclient.util.MiscUtils.withDefault;
import static io.netty.handler.codec.http.HttpHeaders.Values.*;

import java.nio.charset.Charset;

/**
 * This class is an adaptation of the Apache HttpClient implementation
 */
public abstract class FileLikePart extends PartBase {

    /**
     * Default content encoding of file attachments.
     */
    public static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private String fileName;

    /**
     * FilePart Constructor.
     * 
     * @param name the name for this part
     * @param contentType the content type for this part, if <code>null</code> the {@link #DEFAULT_CONTENT_TYPE default} is used
     * @param charset the charset encoding for this part
     * @param contentId the content id
     * @param transfertEncoding the transfer encoding
     */
    public FileLikePart(String name, String contentType, Charset charset, String contentId, String transfertEncoding) {
        super(name,//
                withDefault(contentType, DEFAULT_CONTENT_TYPE),//
                charset,//
                contentId,//
                withDefault(transfertEncoding, BINARY));
    }

    public final void setFileName(String fileName) {
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
