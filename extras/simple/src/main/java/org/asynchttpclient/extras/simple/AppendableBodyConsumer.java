/*
 * Copyright (c) 2010-2013 Sonatype, Inc. All rights reserved.
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
package org.asynchttpclient.extras.simple;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * An {@link Appendable} customer for {@link ByteBuffer}
 */
public class AppendableBodyConsumer implements BodyConsumer {

  private final Appendable appendable;
  private final Charset charset;

  public AppendableBodyConsumer(Appendable appendable, Charset charset) {
    this.appendable = appendable;
    this.charset = charset;
  }

  public AppendableBodyConsumer(Appendable appendable) {
    this.appendable = appendable;
    this.charset = UTF_8;
  }

  @Override
  public void consume(ByteBuffer byteBuffer) throws IOException {
    appendable
            .append(new String(byteBuffer.array(), byteBuffer.arrayOffset() + byteBuffer.position(), byteBuffer.remaining(), charset));
  }

  @Override
  public void close() throws IOException {
    if (appendable instanceof Closeable) {
      Closeable.class.cast(appendable).close();
    }
  }
}
