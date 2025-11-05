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

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

/**
 * A {@link BodyConsumer} that writes response body bytes to a {@link RandomAccessFile}.
 * <p>
 * This consumer supports resumable downloads by tracking the number of bytes written
 * to the file and allowing the download to continue from the current file position.
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * RandomAccessFile raf = new RandomAccessFile("output.dat", "rw");
 * FileBodyConsumer consumer = new FileBodyConsumer(raf);
 *
 * SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
 *     .setUrl("http://www.example.com/large-file.zip")
 *     .setResumableDownload(true)
 *     .build();
 *
 * Future<Response> future = client.get(consumer);
 * }</pre>
 */
public class FileBodyConsumer implements ResumableBodyConsumer {

  private final RandomAccessFile file;

  /**
   * Creates a new FileBodyConsumer that writes to the specified RandomAccessFile.
   *
   * @param file the RandomAccessFile to write response body bytes to
   */
  public FileBodyConsumer(RandomAccessFile file) {
    this.file = file;
  }

  /**
   * Writes the received bytes to the underlying file.
   *
   * @param byteBuffer the buffer containing response body bytes
   * @throws IOException if an I/O error occurs during writing
   */
  @Override
  public void consume(ByteBuffer byteBuffer) throws IOException {
    // TODO: Channel.transferFrom may be a good idea to investigate.
    file.write(byteBuffer.array(), byteBuffer.arrayOffset() + byteBuffer.position(), byteBuffer.remaining());
  }

  /**
   * Closes the underlying RandomAccessFile.
   *
   * @throws IOException if an I/O error occurs during closing
   */
  @Override
  public void close() throws IOException {
    file.close();
  }

  /**
   * Returns the number of bytes already written to the file.
   * <p>
   * This is used for resumable downloads to determine where to continue downloading.
   *
   * @return the current file length in bytes
   * @throws IOException if an I/O error occurs reading the file length
   */
  @Override
  public long getTransferredBytes() throws IOException {
    return file.length();
  }

  /**
   * Prepares the file for resuming a download by seeking to the end.
   * <p>
   * This positions the file pointer at the end so new bytes will be appended.
   *
   * @throws IOException if an I/O error occurs during seeking
   */
  @Override
  public void resume() throws IOException {
    file.seek(getTransferredBytes());
  }
}
