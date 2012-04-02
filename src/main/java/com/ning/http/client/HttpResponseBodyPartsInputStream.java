/*
 * Copyright 2010 Ning, Inc.
 * 
 * Ning licenses this file to you under the Apache License, version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at:
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.ning.http.client;

import java.io.IOException;
import java.io.InputStream;

/**
 * An {@link InputStream} that reads all the elements in an array of {@link HttpResponseBodyPart}s.
 */
public class HttpResponseBodyPartsInputStream extends InputStream {

    private final HttpResponseBodyPart[] parts;

    private int currentPos = 0;
    private int bytePos = -1;
    private byte[] active;
    private int available = 0;

    public HttpResponseBodyPartsInputStream(HttpResponseBodyPart[] parts) {
        this.parts = parts;
        active = parts[0].getBodyPartBytes();
        computeLength(parts);
    }

    private void computeLength(HttpResponseBodyPart[] parts) {
        if (available == 0) {
            for (HttpResponseBodyPart p : parts) {
                available += p.getBodyPartBytes().length;
            }
        }
    }

    @Override
    public int available() throws IOException {
        return available;
    }

    @Override
    public int read() throws IOException {
        if (++bytePos >= active.length) {
            // No more bytes, so step to the next array.
            if (++currentPos >= parts.length) {
                return -1;
            }

            bytePos = 0;
            active = parts[currentPos].getBodyPartBytes();
        }

        return active[bytePos] & 0xFF;
    }
}