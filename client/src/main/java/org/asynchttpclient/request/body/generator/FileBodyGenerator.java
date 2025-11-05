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

import org.asynchttpclient.request.body.RandomAccessBody;

import java.io.File;

import static org.asynchttpclient.util.Assertions.assertNotNull;

/**
 * Creates a request body from the contents of a file.
 * <p>
 * This body generator supports reading the entire file or a specific region within
 * the file. It implements {@link BodyGenerator} and returns a {@link RandomAccessBody}
 * to enable efficient zero-copy file transfers using Netty's file region support.
 * </p>
 * <p>
 * Note: The {@link #createBody()} method is not actually invoked during request
 * processing. Instead, Netty directly sends the file using its optimized file
 * transfer mechanisms.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Create a body generator for an entire file
 * File file = new File("upload.dat");
 * BodyGenerator generator = new FileBodyGenerator(file);
 *
 * // Create a body generator for a specific region of a file
 * BodyGenerator regionGenerator = new FileBodyGenerator(file, 1024, 2048);
 *
 * // Use with AsyncHttpClient
 * AsyncHttpClient client = asyncHttpClient();
 * client.preparePost("http://example.com/upload")
 *     .setBody(generator)
 *     .execute();
 * }</pre>
 */
public final class FileBodyGenerator implements BodyGenerator {

  private final File file;
  private final long regionSeek;
  private final long regionLength;

  /**
   * Constructs a file body generator for the entire file.
   *
   * @param file the file to read from
   */
  public FileBodyGenerator(File file) {
    this(file, 0L, file.length());
  }

  /**
   * Constructs a file body generator for a specific region of the file.
   *
   * @param file         the file to read from
   * @param regionSeek   the offset in bytes from the start of the file
   * @param regionLength the number of bytes to read from the file
   */
  public FileBodyGenerator(File file, long regionSeek, long regionLength) {
    this.file = assertNotNull(file, "file");
    this.regionLength = regionLength;
    this.regionSeek = regionSeek;
  }

  /**
   * Gets the file that this generator reads from.
   *
   * @return the file
   */
  public File getFile() {
    return file;
  }

  /**
   * Gets the length of the region to read from the file.
   *
   * @return the region length in bytes
   */
  public long getRegionLength() {
    return regionLength;
  }

  /**
   * Gets the offset within the file where reading should start.
   *
   * @return the region seek offset in bytes
   */
  public long getRegionSeek() {
    return regionSeek;
  }

  /**
   * Creates a body instance.
   * <p>
   * Note: This method is not actually used during request processing. Netty directly
   * sends the file using its optimized file transfer mechanisms, so calling this
   * method will throw an exception.
   * </p>
   *
   * @return never returns normally
   * @throws UnsupportedOperationException always thrown as this method is not used
   */
  @Override
  public RandomAccessBody createBody() {
    throw new UnsupportedOperationException("FileBodyGenerator.createBody isn't used, Netty directly sends the file");
  }
}
