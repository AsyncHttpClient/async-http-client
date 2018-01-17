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
 * A request body which supports random access to its contents.
 */
public interface RandomAccessBody extends Body {

  /**
   * Transfers the specified chunk of bytes from this body to the specified channel.
   *
   * @param target The destination channel to transfer the body chunk to, must not be {@code null}.
   * @return The non-negative number of bytes actually transferred.
   * @throws IOException If the body chunk could not be transferred.
   */
  long transferTo(WritableByteChannel target) throws IOException;
}
