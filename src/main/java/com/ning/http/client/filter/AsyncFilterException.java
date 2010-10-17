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
package com.ning.http.client.filter;

/**
 * An exception that can be thrown by an {@link com.ning.http.client.AsyncHandler} to interrupt the request processing.
 */
public class AsyncFilterException extends Exception {

    /**
     * @param message
     */
    public AsyncFilterException(final String message) {
        super(message);
    }

    /**
     * @param message
     * @param cause
     */
    public AsyncFilterException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
