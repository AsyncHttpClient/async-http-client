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

import java.nio.charset.Charset;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.asynchttpclient.util.MiscUtils.withDefault;

public class StringPart extends PartBase {

    /**
     * Default charset of string parameters
     */
    private static final Charset DEFAULT_CHARSET = UTF_8;

    /**
     * Contents of this StringPart.
     */
    private final String value;

    public StringPart(String name, String value) {
        this(name, value, null);
    }

    public StringPart(String name, String value, String contentType) {
        this(name, value, contentType, null);
    }

    public StringPart(String name, String value, String contentType, Charset charset) {
        this(name, value, contentType, charset, null);
    }

    public StringPart(String name, String value, String contentType, Charset charset, String contentId) {
        this(name, value, contentType, charset, contentId, null);
    }

    public StringPart(String name, String value, String contentType, Charset charset, String contentId, String transferEncoding) {
        super(name, contentType, charsetOrDefault(charset), contentId, transferEncoding);
        requireNonNull(value, "value");

        // See RFC 2048, 2.8. "8bit Data"
        if (value.indexOf(0) != -1) {
            throw new IllegalArgumentException("NULs may not be present in string parts");
        }

        this.value = value;
    }

    private static Charset charsetOrDefault(Charset charset) {
        return withDefault(charset, DEFAULT_CHARSET);
    }

    public String getValue() {
        return value;
    }
}
