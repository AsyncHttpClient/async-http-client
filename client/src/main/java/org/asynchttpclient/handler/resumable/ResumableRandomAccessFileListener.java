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
 * A {@link ResumableListener} implementation that writes response body bytes to a {@link RandomAccessFile}.
 * <p>
 * This listener enables resumable file downloads by using a RandomAccessFile that can seek to
 * specific positions. When resuming a download, the file pointer is positioned at the end of
 * the existing file content, allowing new bytes to be appended seamlessly.
 * <p>
 * The listener automatically seeks to the end of the file before each write operation,
 * ensuring proper positioning even when resuming from a previous download attempt.
 *
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * File outputFile = new File("largefile.zip");
 * RandomAccessFile raf = new RandomAccessFile(outputFile, "rw");
 *
 * ResumableRandomAccessFileListener listener = new ResumableRandomAccessFileListener(raf);
 * ResumableAsyncHandler handler = new ResumableAsyncHandler();
 * handler.setResumableListener(listener);
 *
 * // If the download is interrupted and resumed, the file will continue from where it left off
 * client.prepareGet("http://example.com/largefile.zip")
 *     .execute(handler)
 *     .get();
 * }</pre>
 */
public class ResumableRandomAccessFileListener implements ResumableListener {
  private final RandomAccessFile file;

  /**
   * Creates a new listener that writes to the specified RandomAccessFile.
   *
   * @param file the RandomAccessFile where response body bytes will be written
   */
  public ResumableRandomAccessFileListener(RandomAccessFile file) {
    this.file = file;
  }

  /**
   * Writes a chunk of response body bytes to the file.
   * <p>
   * This method positions the file pointer at the end of the file before writing,
   * enabling resumable downloads. The method handles both heap and direct ByteBuffers.
   *
   * @param buffer the ByteBuffer containing response body bytes
   * @throws IOException if an I/O error occurs while writing to the file
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
   * Invoked when all response bytes have been received.
   * <p>
   * Closes the RandomAccessFile to release system resources.
   */
  public void onAllBytesReceived() {
    closeSilently(file);
  }

  /**
   * Returns the current length of the file in bytes.
   * <p>
   * This is used to determine the resume position when continuing an interrupted download.
   *
   * @return the current file length in bytes, or 0 if an error occurs
   */
  public long length() {
    try {
      return file.length();
    } catch (IOException e) {
      return 0;
    }
  }
}
