/*
 *    Copyright (c) 2014-2024 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.request.body.multipart;

import jakarta.activation.MimetypesFileTypeMap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

import static org.asynchttpclient.util.MiscUtils.withDefault;

/**
 * This class is an adaptation of the Apache HttpClient implementation
 */
public abstract class FileLikePart extends PartBase {

    private static final MimetypesFileTypeMap MIME_TYPES_FILE_TYPE_MAP;

    static {
        try (InputStream is = FileLikePart.class.getResourceAsStream("ahc-mime.types")) {
            MIME_TYPES_FILE_TYPE_MAP = new MimetypesFileTypeMap(is);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Default content encoding of file attachments.
     */
    private final String fileName;

    /**
     * FilePart Constructor.
     *
     * @param name             the name for this part
     * @param contentType      the content type for this part, if {@code null} try to figure out from the fileName mime type
     * @param charset          the charset encoding for this part
     * @param fileName         the fileName
     * @param contentId        the content id
     * @param transferEncoding the transfer encoding
     */
    protected FileLikePart(String name, String contentType, Charset charset, String fileName, String contentId, String transferEncoding) {
        super(name,
                computeContentType(contentType, fileName),
                charset,
                contentId,
                transferEncoding);
        this.fileName = fileName;
    }

    private static String computeContentType(String contentType, String fileName) {
        return contentType != null ? contentType : MIME_TYPES_FILE_TYPE_MAP.getContentType(withDefault(fileName, ""));
    }

    public String getFileName() {
        return fileName;
    }

    @Override
    public String toString() {
        return super.toString() + " filename=" + fileName;
    }
}
