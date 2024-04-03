/*
 *    Copyright (c) 2018-2023 AsyncHttpClient Project. All rights reserved.
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

import java.io.InputStream;
import java.nio.charset.Charset;

import static java.util.Objects.requireNonNull;

public class InputStreamPart extends FileLikePart {

    private final InputStream inputStream;
    private final long contentLength;

    public InputStreamPart(String name, InputStream inputStream, String fileName) {
        this(name, inputStream, fileName, -1);
    }

    public InputStreamPart(String name, InputStream inputStream, String fileName, long contentLength) {
        this(name, inputStream, fileName, contentLength, null);
    }

    public InputStreamPart(String name, InputStream inputStream, String fileName, long contentLength, String contentType) {
        this(name, inputStream, fileName, contentLength, contentType, null);
    }

    public InputStreamPart(String name, InputStream inputStream, String fileName, long contentLength, String contentType, Charset charset) {
        this(name, inputStream, fileName, contentLength, contentType, charset, null);
    }

    public InputStreamPart(String name, InputStream inputStream, String fileName, long contentLength, String contentType, Charset charset, String contentId) {
        this(name, inputStream, fileName, contentLength, contentType, charset, contentId, null);
    }

    public InputStreamPart(String name, InputStream inputStream, String fileName, long contentLength, String contentType, Charset charset, String contentId,
                           String transferEncoding) {
        super(name, contentType, charset, fileName, contentId, transferEncoding);
        this.inputStream = requireNonNull(inputStream, "inputStream");
        this.contentLength = contentLength;
    }

    public InputStream getInputStream() {
        return inputStream;
    }

    public long getContentLength() {
        return contentLength;
    }
}
