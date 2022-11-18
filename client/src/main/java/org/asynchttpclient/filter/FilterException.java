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
package org.asynchttpclient.filter;

/**
 * An exception that can be thrown by an {@link org.asynchttpclient.AsyncHandler} to interrupt invocation of
 * the {@link RequestFilter} and {@link ResponseFilter}. It also interrupt the request and response processing.
 */
@SuppressWarnings("serial")
public class FilterException extends Exception {

    public FilterException(final String message) {
        super(message);
    }

    public FilterException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
