/*
* Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
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

package com.ning.http.client;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A request body.
 */
public interface Body {

    /**
     * Gets the length of the body.
     *
     * @return The length of the body in bytes, or negative if unknown.
     */
    long getContentLength();

    /**
     * Reads the next chunk of bytes from the body.
     *
     * @param buffer The buffer to store the chunk in, must not be {@code null}.
     * @return The non-negative number of bytes actually read or {@code -1} if the body has been read completely.
     * @throws IOException If the chunk could not be read.
     */
    long read(ByteBuffer buffer) throws IOException;

    /**
     * Releases any resources associated with this body.
     *
     * @throws IOException
     */
    void close() throws IOException;

}
