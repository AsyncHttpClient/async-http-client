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

import static org.asynchttpclient.util.Assertions.assertNotNull;

import java.nio.charset.Charset;

import org.asynchttpclient.util.ArrayUtils;

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
        super(name, contentType, charset, contentId, transferEncoding);
        assertNotNull(bytes, "bytes");
        this.bytes = ArrayUtils.copyOf(bytes);
        setFileName(fileName);
    }

    public byte[] getBytes() {
        return bytes;
    }
}
