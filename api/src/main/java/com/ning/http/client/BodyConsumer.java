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
 * A simple API to be used with the {@link SimpleAsyncHttpClient} class in order to process response's bytes.
 */
public interface BodyConsumer {

    /**
     * Consume the received bytes.
     *
     * @param byteBuffer a {@link ByteBuffer} represntation of the response's chunk.
     * @throws IOException
     */
    void consume(ByteBuffer byteBuffer) throws IOException;

    /**
     * Invoked when all the response bytes has been processed.
     *
     * @throws IOException
     */
    void close() throws IOException;

}
