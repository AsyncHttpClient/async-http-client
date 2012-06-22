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

/**
 * Creates a request body.
 */
public interface BodyGenerator {

    /**
     * Creates a new instance of the request body to be read. While each invocation of this method is supposed to create
     * a fresh instance of the body, the actual contents of all these body instances is the same. For example, the body
     * needs to be resend after an authentication challenge of a redirect.
     *
     * @return The request body, never {@code null}.
     * @throws IOException If the body could not be created.
     */
    Body createBody()
            throws IOException;

}
