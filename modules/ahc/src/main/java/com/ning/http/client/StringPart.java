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
 *
 */
package com.ning.http.client;

/**
 * A string multipart part.
 */
public class StringPart implements Part {
    private final String name;
    private final String value;
    private final String charset;

    public StringPart(String name, String value, String charset) {
        this.name = name;
        this.value = value;
        this.charset = charset;
    }

    public StringPart(String name, String value) {
        this.name = name;
        this.value = value;
        this.charset = "UTF-8";
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    public String getCharset() {
        return charset;
    }

}