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

package org.asynchttpclient.extras.simple;

import java.io.IOException;

/**
 * A {@link BodyConsumer} that supports resuming interrupted downloads.
 * <p>
 * This interface extends BodyConsumer to provide methods for tracking the number
 * of bytes already transferred and resuming from that position. This is useful
 * for large file downloads that may be interrupted and need to continue from
 * where they left off.
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * RandomAccessFile file = new RandomAccessFile("large-file.zip", "rw");
 * ResumableBodyConsumer consumer = new FileBodyConsumer(file);
 *
 * SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
 *     .setUrl("http://www.example.com/large-file.zip")
 *     .setResumableDownload(true)
 *     .build();
 *
 * // Download will resume from current file position if interrupted
 * Future<Response> future = client.get(consumer);
 * }</pre>
 *
 * @author Benjamin Hanzelmann
 */
public interface ResumableBodyConsumer extends BodyConsumer {

  /**
   * Prepares this consumer to resume a download from the current position.
   * <p>
   * For example, a file-based implementation would seek to the end of the file
   * so that new bytes will be appended.
   *
   * @throws IOException if an I/O error occurs during preparation
   */
  void resume() throws IOException;

  /**
   * Returns the number of bytes already transferred.
   * <p>
   * For example, a file-based implementation would return the current file size.
   * This value is used to send a Range header to continue the download.
   *
   * @return the number of transferred bytes
   * @throws IOException if an I/O error occurs reading the transferred byte count
   */
  long getTransferredBytes() throws IOException;
}
