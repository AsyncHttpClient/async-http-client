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
package org.asynchttpclient.handler.resumable;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

import static org.asynchttpclient.util.MiscUtils.closeSilently;

/**
 * A {@link org.asynchttpclient.handler.resumable.ResumableListener} which use a {@link RandomAccessFile} for storing the received bytes.
 */
public class ResumableRandomAccessFileListener implements ResumableListener {
  private final RandomAccessFile file;

  public ResumableRandomAccessFileListener(RandomAccessFile file) {
    this.file = file;
  }

  /**
   * This method uses the last valid bytes written on disk to position a {@link RandomAccessFile}, allowing
   * resumable file download.
   *
   * @param buffer a {@link ByteBuffer}
   * @throws IOException exception while writing into the file
   */
  public void onBytesReceived(ByteBuffer buffer) throws IOException {
    file.seek(file.length());
    if (buffer.hasArray()) {
      file.write(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
    } else { // if the buffer is direct or backed by a String...
      byte[] b = new byte[buffer.remaining()];
      int pos = buffer.position();
      buffer.get(b);
      buffer.position(pos);
      file.write(b);
    }
  }

  /**
   * {@inheritDoc}
   */
  public void onAllBytesReceived() {
    closeSilently(file);
  }

  /**
   * {@inheritDoc}
   */
  public long length() {
    try {
      return file.length();
    } catch (IOException e) {
      return 0;
    }
  }
}
