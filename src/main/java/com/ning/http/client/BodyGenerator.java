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
 *
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
