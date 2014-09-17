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
 */
package com.ning.http.client.providers.jdk;

import static java.nio.charset.StandardCharsets.*;
import static com.ning.http.util.MiscUtils.isNonEmpty;

import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.multipart.MultipartUtils;
import com.ning.http.client.multipart.Part;
import com.ning.http.client.multipart.RequestEntity;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Random;

/**
 * This class is an adaptation of the Apache HttpClient implementation
 * 
 * @link http://hc.apache.org/httpclient-3.x/
 */
public class MultipartRequestEntity implements RequestEntity {

    /**
     * The Content-Type for multipart/form-data.
     */
    private static final String MULTIPART_FORM_CONTENT_TYPE = "multipart/form-data";

    /**
     * The pool of ASCII chars to be used for generating a multipart boundary.
     */
    private static byte[] MULTIPART_CHARS = "-_1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(US_ASCII);

    /**
     * Generates a random multipart boundary string.
     * 
     * @return
     */
    public static byte[] generateMultipartBoundary() {
        Random rand = new Random();
        byte[] bytes = new byte[rand.nextInt(11) + 30]; // a random size from 30 to 40
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = MULTIPART_CHARS[rand.nextInt(MULTIPART_CHARS.length)];
        }
        return bytes;
    }

    /**
     * The MIME parts as set by the constructor
     */
    protected final List<Part> parts;

    private final byte[] multipartBoundary;

    private final String contentType;
    
    private final long contentLength;

    /**
     * Creates a new multipart entity containing the given parts.
     * @param parts The parts to include.
     */
    public MultipartRequestEntity(List<Part> parts, FluentCaseInsensitiveStringsMap requestHeaders) {
        if (parts == null)
            throw new NullPointerException("parts");
        this.parts = parts;
        String contentTypeHeader = requestHeaders.getFirstValue("Content-Type");
        if (isNonEmpty(contentTypeHeader)) {
        	int boundaryLocation = contentTypeHeader.indexOf("boundary=");
        	if (boundaryLocation != -1) {
        		// boundary defined in existing Content-Type
        		contentType = contentTypeHeader;
        		multipartBoundary = (contentTypeHeader.substring(boundaryLocation + "boundary=".length()).trim()).getBytes(US_ASCII);
        	} else {
        		// generate boundary and append it to existing Content-Type
        		multipartBoundary = generateMultipartBoundary();
                contentType = computeContentType(contentTypeHeader);
        	}
        } else {
        	multipartBoundary = generateMultipartBoundary();
            contentType = computeContentType(MULTIPART_FORM_CONTENT_TYPE);
        }
        
        contentLength = MultipartUtils.getLengthOfParts(parts, multipartBoundary);
    }

    private String computeContentType(String base) {
    	StringBuilder buffer = new StringBuilder(base);
		if (!base.endsWith(";"))
			buffer.append(";");
        return buffer.append(" boundary=").append(new String(multipartBoundary, US_ASCII)).toString();
    }

    /**
     * Returns the MIME boundary string that is used to demarcate boundaries of this part.
     * 
     * @return The boundary string of this entity in ASCII encoding.
     */
    public byte[] getMultipartBoundary() {
        return multipartBoundary;
    }

    public void writeRequest(OutputStream out) throws IOException {
        for (Part part : parts) {
            part.write(out, multipartBoundary);
        }
    }

    public long getContentLength() {
        return contentLength;
    }

    public String getContentType() {
        return contentType;
    }
}
