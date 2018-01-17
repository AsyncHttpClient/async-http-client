/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.netty.request.body;

import io.netty.channel.FileRegion;
import io.netty.util.AbstractReferenceCounted;
import org.asynchttpclient.request.body.RandomAccessBody;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;

import static org.asynchttpclient.util.Assertions.assertNotNull;
import static org.asynchttpclient.util.MiscUtils.closeSilently;

/**
 * Adapts a {@link RandomAccessBody} to Netty's {@link FileRegion}.
 */
class BodyFileRegion extends AbstractReferenceCounted implements FileRegion {

  private final RandomAccessBody body;
  private long transferred;

  BodyFileRegion(RandomAccessBody body) {
    this.body = assertNotNull(body, "body");
  }

  @Override
  public long position() {
    return 0;
  }

  @Override
  public long count() {
    return body.getContentLength();
  }

  @Override
  public long transfered() {
    return transferred();
  }

  @Override
  public long transferred() {
    return transferred;
  }

  @Override
  public FileRegion retain() {
    super.retain();
    return this;
  }

  @Override
  public FileRegion retain(int arg0) {
    super.retain(arg0);
    return this;
  }

  @Override
  public FileRegion touch() {
    return this;
  }

  @Override
  public FileRegion touch(Object arg0) {
    return this;
  }

  @Override
  public long transferTo(WritableByteChannel target, long position) throws IOException {
    long written = body.transferTo(target);
    if (written > 0) {
      transferred += written;
    }
    return written;
  }

  @Override
  protected void deallocate() {
    closeSilently(body);
  }
}
