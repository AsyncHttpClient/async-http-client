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

package org.asynchttpclient.request.body;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;

/**
 * A request body that supports random access to its contents via channel-based transfer.
 * <p>
 * This interface extends {@link Body} to provide an additional transfer method using
 * {@link WritableByteChannel}, which enables efficient zero-copy transfer for certain
 * types of bodies (e.g., file-based bodies). This is particularly useful for HTTP
 * connections where zero-copy optimizations can be applied.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * RandomAccessBody body = new FileBodyGenerator(new File("data.bin")).createBody();
 * try (FileChannel channel = FileChannel.open(outputPath, StandardOpenOption.WRITE)) {
 *     long transferred = body.transferTo(channel);
 *     System.out.println("Transferred " + transferred + " bytes");
 * } finally {
 *     body.close();
 * }
 * }</pre>
 */
public interface RandomAccessBody extends Body {

  /**
   * Transfers bytes from this body to the specified writable channel.
   * <p>
   * This method performs an efficient transfer of body content to the target channel,
   * potentially using zero-copy optimizations when supported by the underlying
   * implementation. The transfer is typically more efficient than buffer-based
   * transfers for file-based bodies.
   * </p>
   *
   * @param target the destination channel to transfer the body chunk to, must not be {@code null}
   * @return the non-negative number of bytes actually transferred
   * @throws IOException if the body chunk could not be transferred due to an I/O error
   */
  long transferTo(WritableByteChannel target) throws IOException;
}
