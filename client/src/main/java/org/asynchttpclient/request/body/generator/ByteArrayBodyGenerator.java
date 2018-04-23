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
package org.asynchttpclient.request.body.generator;

import io.netty.buffer.ByteBuf;
import org.asynchttpclient.request.body.Body;

/**
 * A {@link BodyGenerator} backed by a byte array.
 */
public final class ByteArrayBodyGenerator implements BodyGenerator {

  private final byte[] bytes;

  public ByteArrayBodyGenerator(byte[] bytes) {
    this.bytes = bytes;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Body createBody() {
    return new ByteBody();
  }

  protected final class ByteBody implements Body {
    private boolean eof = false;
    private int lastPosition = 0;

    public long getContentLength() {
      return bytes.length;
    }

    public BodyState transferTo(ByteBuf target) {

      if (eof) {
        return BodyState.STOP;
      }

      final int remaining = bytes.length - lastPosition;
      final int initialTargetWritableBytes = target.writableBytes();
      if (remaining <= initialTargetWritableBytes) {
        target.writeBytes(bytes, lastPosition, remaining);
        eof = true;
      } else {
        target.writeBytes(bytes, lastPosition, initialTargetWritableBytes);
        lastPosition += initialTargetWritableBytes;
      }
      return BodyState.CONTINUE;
    }

    public void close() {
      lastPosition = 0;
      eof = false;
    }
  }
}
