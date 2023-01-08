/*
 *    Copyright (c) 2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.request.body.multipart.part;

import org.asynchttpclient.request.body.multipart.FileLikePart;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

public abstract class FileLikeMultipartPart<T extends FileLikePart> extends MultipartPart<T> {

    /**
     * Attachment's file name as a byte array
     */
    private static final byte[] FILE_NAME_BYTES = "; filename=".getBytes(US_ASCII);

    FileLikeMultipartPart(T part, byte[] boundary) {
        super(part, boundary);
    }

    @Override
    protected void visitDispositionHeader(PartVisitor visitor) {
        super.visitDispositionHeader(visitor);
        if (part.getFileName() != null) {
            visitor.withBytes(FILE_NAME_BYTES);
            visitor.withByte(QUOTE_BYTE);
            visitor.withBytes(part.getFileName().getBytes(part.getCharset() != null ? part.getCharset() : UTF_8));
            visitor.withByte(QUOTE_BYTE);
        }
    }
}
