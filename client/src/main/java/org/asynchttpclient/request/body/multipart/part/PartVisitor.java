/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.request.body.multipart.part;

import io.netty.buffer.ByteBuf;

/**
 * Visitor interface for building multipart part headers and boundaries.
 * <p>
 * This interface implements the Visitor pattern for constructing multipart part data.
 * It provides methods for appending bytes and is used by {@link MultipartPart} to
 * build pre-content and post-content buffers. Two implementations are provided:
 * {@link CounterPartVisitor} for computing sizes and {@link ByteBufVisitor} for
 * writing actual data.
 * </p>
 */
public interface PartVisitor {

  /**
   * Visits a byte array, incorporating it into the part being built.
   *
   * @param bytes the byte array to visit
   */
  void withBytes(byte[] bytes);

  /**
   * Visits a single byte, incorporating it into the part being built.
   *
   * @param b the byte to visit
   */
  void withByte(byte b);

  /**
   * A visitor implementation that counts the total number of bytes visited.
   * <p>
   * This visitor is used to compute the size of pre-content and post-content sections
   * before allocating buffers for them.
   * </p>
   */
  class CounterPartVisitor implements PartVisitor {

    private int count = 0;

    /**
     * Increments the count by the length of the byte array.
     *
     * @param bytes the byte array being visited
     */
    @Override
    public void withBytes(byte[] bytes) {
      count += bytes.length;
    }

    /**
     * Increments the count by one.
     *
     * @param b the byte being visited
     */
    @Override
    public void withByte(byte b) {
      count++;
    }

    /**
     * Returns the total count of bytes visited.
     *
     * @return the total byte count
     */
    public int getCount() {
      return count;
    }
  }

  /**
   * A visitor implementation that writes bytes to a ByteBuf.
   * <p>
   * This visitor is used to actually write the pre-content and post-content data
   * to allocated buffers that will be transferred.
   * </p>
   */
  class ByteBufVisitor implements PartVisitor {
    private final ByteBuf target;

    /**
     * Constructs a ByteBuf visitor with the specified target buffer.
     *
     * @param target the buffer to write bytes to
     */
    public ByteBufVisitor(ByteBuf target) {
      this.target = target;
    }

    /**
     * Writes the byte array to the target buffer.
     *
     * @param bytes the byte array to write
     */
    @Override
    public void withBytes(byte[] bytes) {
      target.writeBytes(bytes);
    }

    /**
     * Writes the single byte to the target buffer.
     *
     * @param b the byte to write
     */
    @Override
    public void withByte(byte b) {
      target.writeByte(b);
    }
  }
}
