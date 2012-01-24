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
package com.ning.http.client.consumers;

import com.ning.http.client.BodyConsumer;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * An {@link Appendable} customer for {@link ByteBuffer}
 */
public class AppendableBodyConsumer implements BodyConsumer {

    private final Appendable appendable;
    private final String encoding;

    public AppendableBodyConsumer(Appendable appendable, String encoding) {
        this.appendable = appendable;
        this.encoding = encoding;
    }

    public AppendableBodyConsumer(Appendable appendable) {
        this.appendable = appendable;
        this.encoding = "UTF-8";
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public void consume(ByteBuffer byteBuffer) throws IOException {
        appendable.append(new String(byteBuffer.array(), encoding));
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public void close() throws IOException {
        if (Closeable.class.isAssignableFrom(appendable.getClass())) {
            Closeable.class.cast(appendable).close();
        }
    }
}
