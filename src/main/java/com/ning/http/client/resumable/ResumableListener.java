/*
* Copyright 2010 Ning, Inc.
*
* Ning licenses this file to you under the Apache License, version 2.0
* (the "License"); you may not use this file except in compliance with the
* License. You may obtain a copy of the License at:
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
* WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
* License for the specific language governing permissions and limitations
* under the License.
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
    public void onBytesReceived(ByteBuffer byteBuffer) throws IOException;

    /**
     * Invoked when all the bytes has been sucessfully transferred.
     */
    public void onAllBytesReceived();

    /**
     * Return the length of previously downloaded bytes.
     *
     * @return the length of previously downloaded bytes
     */
    public long length();

}