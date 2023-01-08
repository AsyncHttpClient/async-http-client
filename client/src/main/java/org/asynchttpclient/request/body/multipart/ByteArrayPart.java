/*
 *    Copyright (c) 2014-2023 AsyncHttpClient Project. All rights reserved.
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

import java.nio.charset.Charset;

import static org.asynchttpclient.util.Assertions.assertNotNull;

public class ByteArrayPart extends FileLikePart {

    private final byte[] bytes;

    public ByteArrayPart(String name, byte[] bytes) {
        this(name, bytes, null);
    }

    public ByteArrayPart(String name, byte[] bytes, String contentType) {
        this(name, bytes, contentType, null);
    }

    public ByteArrayPart(String name, byte[] bytes, String contentType, Charset charset) {
        this(name, bytes, contentType, charset, null);
    }

    public ByteArrayPart(String name, byte[] bytes, String contentType, Charset charset, String fileName) {
        this(name, bytes, contentType, charset, fileName, null);
    }

    public ByteArrayPart(String name, byte[] bytes, String contentType, Charset charset, String fileName, String contentId) {
        this(name, bytes, contentType, charset, fileName, contentId, null);
    }

    public ByteArrayPart(String name, byte[] bytes, String contentType, Charset charset, String fileName, String contentId, String transferEncoding) {
        super(name, contentType, charset, fileName, contentId, transferEncoding);
        this.bytes = assertNotNull(bytes, "bytes");
    }

    public byte[] getBytes() {
        return bytes;
    }
}
