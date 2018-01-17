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
 */
public final class FileBodyGenerator implements BodyGenerator {

  private final File file;
  private final long regionSeek;
  private final long regionLength;

  public FileBodyGenerator(File file) {
    this(file, 0L, file.length());
  }

  public FileBodyGenerator(File file, long regionSeek, long regionLength) {
    this.file = assertNotNull(file, "file");
    this.regionLength = regionLength;
    this.regionSeek = regionSeek;
  }

  public File getFile() {
    return file;
  }

  public long getRegionLength() {
    return regionLength;
  }

  public long getRegionSeek() {
    return regionSeek;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public RandomAccessBody createBody() {
    throw new UnsupportedOperationException("FileBodyGenerator.createBody isn't used, Netty direclt sends the file");
  }
}
