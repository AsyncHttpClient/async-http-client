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

/**
 * An extended {@link AsyncHandler} with two extra callback who get invoked during the content upload to a remote server.
 * This {@link AsyncHandler} must be used only with PUT and POST request.
 */
public interface ProgressAsyncHandler<T> extends AsyncHandler<T> {

    /**
     * Invoked when the content (a {@link java.io.File}, {@link String} or {@link java.io.FileInputStream} has been fully
     * written on the I/O socket.
     *
     * @return a {@link com.ning.http.client.AsyncHandler.STATE} telling to CONTINUE or ABORT the current processing.
     */
    STATE onHeaderWriteCompleted();

    /**
     * Invoked when the content (a {@link java.io.File}, {@link String} or {@link java.io.FileInputStream} has been fully
     * written on the I/O socket.
     *
     * @return a {@link com.ning.http.client.AsyncHandler.STATE} telling to CONTINUE or ABORT the current processing.
     */
    STATE onContentWriteCompleted();

    /**
     * Invoked when the I/O operation associated with the {@link Request} body wasn't fully written in a single I/O write
     * operation. This method is never invoked if the write operation complete in a sinfle I/O write.
     *
     * @param amount  The amount of bytes to transfer.
     * @param current The amount of bytes transferred
     * @param total   The total number of bytes transferred
     * @return a {@link com.ning.http.client.AsyncHandler.STATE} telling to CONTINUE or ABORT the current processing.
     */
    STATE onContentWriteProgress(long amount, long current, long total);

}
