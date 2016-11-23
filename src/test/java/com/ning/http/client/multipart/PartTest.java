/*
 * Copyright (c) 2016 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.ning.http.client.multipart;

import static com.ning.http.client.multipart.Part.*;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class PartTest {
    @Test
    public void customContentDisposition() throws IOException {
        ByteArrayPart byteArrayPart = new ByteArrayPart("test", "content".getBytes());
        String customHeader = "file; test=\"param\"";
        byteArrayPart.setCustomContentDisposition(customHeader);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byteArrayPart.write(output, "".getBytes());

        String expectedHeader = new String(CONTENT_DISPOSITION_BYTES) + customHeader + new String(CRLF_BYTES);
        Assert.assertTrue(output.toString().contains(expectedHeader));
    }

    @Test
    public void customContentType() throws IOException {
        ByteArrayPart byteArrayPart = new ByteArrayPart("test", "content".getBytes());
        String customHeader = "blah/blah; test=123";
        byteArrayPart.setCustomContentType(customHeader);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byteArrayPart.write(output, "".getBytes());

        String expectedHeader = new String(CONTENT_TYPE_BYTES) + customHeader + new String(CRLF_BYTES);
        Assert.assertTrue(output.toString().contains(expectedHeader));
    }

    @Test
    public void addKeyValueHeadersCorrectly() throws IOException {
        ByteArrayPart byteArrayPart = new ByteArrayPart("test", "content".getBytes());
        byteArrayPart.addCustomHeader("header-name", "header-value");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byteArrayPart.write(output, "".getBytes());

        String expectedHeader = "header-name" + new String(NAME_VALUE_SEPARATOR_BYTES) + "header-value" + new String(CRLF_BYTES);
        Assert.assertTrue(output.toString().contains(expectedHeader));
    }

    @Test
    public void recognizeSpecialHeadersInAddCustom() throws IOException {
        for (String header : SPECIAL_HEADERS) {
            try {
                ByteArrayPart byteArrayPart = new ByteArrayPart("test", "contents".getBytes(), "plain/txt", null, "file.txt", "my-content-id", "base64");
                byteArrayPart.addCustomHeader(header, "my-value");

                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byteArrayPart.write(output, "".getBytes());

                Assert.fail("Adding " + header + " as custom header should've failed");
            } catch (IllegalArgumentException e) {
                Assert.assertTrue(e.getMessage().contains(header), header);
            }
        }
    }

    public static int countOccurrences(String main, String sub) {
        return (main.length() - main.replace(sub, "").length()) / sub.length();
    }
}
