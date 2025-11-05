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
import java.nio.ByteBuffer;

/**
 * A listener interface for processing response body bytes in a resumable download.
 * <p>
 * Implementations of this interface are used by {@link ResumableAsyncHandler} to
 * receive and store response body bytes. The listener is responsible for:
 * <ul>
 *   <li>Writing received bytes to their final destination (e.g., a file)</li>
 *   <li>Tracking the current download position</li>
 *   <li>Providing the length of previously downloaded bytes for resume operations</li>
 * </ul>
 * <p>
 * The most common implementation is {@link ResumableRandomAccessFileListener}, which
 * writes bytes to a file using {@link java.io.RandomAccessFile} for seek support.
 *
 * <p><b>Usage Example:</b></p>
 * <pre>{@code
 * // Custom implementation example
 * public class CustomResumableListener implements ResumableListener {
 *     private final OutputStream output;
 *     private long bytesWritten = 0;
 *
 *     public CustomResumableListener(OutputStream output) {
 *         this.output = output;
 *     }
 *
 *     @Override
 *     public void onBytesReceived(ByteBuffer buffer) throws IOException {
 *         byte[] bytes = new byte[buffer.remaining()];
 *         buffer.get(bytes);
 *         output.write(bytes);
 *         bytesWritten += bytes.length;
 *     }
 *
 *     @Override
 *     public void onAllBytesReceived() {
 *         try {
 *             output.close();
 *         } catch (IOException e) {
 *             // Handle exception
 *         }
 *     }
 *
 *     @Override
 *     public long length() {
 *         return bytesWritten;
 *     }
 * }
 * }</pre>
 */
public interface ResumableListener {

  /**
   * Invoked when a chunk of response body bytes is available.
   * <p>
   * Implementations should write the bytes to their destination (e.g., file, stream)
   * and update their internal state to track the download progress. This method
   * may be called multiple times for a single response as data arrives in chunks.
   *
   * @param byteBuffer the ByteBuffer containing the response body chunk
   * @throws IOException if an I/O error occurs while processing the bytes
   */
  void onBytesReceived(ByteBuffer byteBuffer) throws IOException;

  /**
   * Invoked when all response body bytes have been successfully received.
   * <p>
   * Implementations should perform cleanup operations such as closing files
   * or streams. This method is called once at the end of a successful download.
   */
  void onAllBytesReceived();

  /**
   * Returns the number of bytes that have been previously downloaded.
   * <p>
   * This value is used to determine the starting position when resuming an
   * interrupted download. It should return the actual number of bytes successfully
   * written to the destination.
   *
   * @return the number of bytes previously downloaded, or 0 if starting fresh
   */
  long length();
}
