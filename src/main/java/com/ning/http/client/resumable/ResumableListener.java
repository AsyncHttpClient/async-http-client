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
package com.ning.http.client.resumable;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A listener class that can be used to digest the bytes from an {@link ResumableAsyncHandler}
 */
public interface ResumableListener {

    /**
     * Invoked when some bytes are available to digest.
     *
     * @param byteBuffer the current bytes
     * @throws IOException
     */
    void onBytesReceived(ByteBuffer byteBuffer) throws IOException;

    /**
     * Invoked when all the bytes has been sucessfully transferred.
     */
    void onAllBytesReceived();

    /**
     * Return the length of previously downloaded bytes.
     *
     * @return the length of previously downloaded bytes
     */
    long length();
}
