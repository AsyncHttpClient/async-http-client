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
     * @throws IOException
     */
    void close() throws IOException;

}
