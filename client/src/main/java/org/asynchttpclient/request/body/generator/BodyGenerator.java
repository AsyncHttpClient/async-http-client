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

import org.asynchttpclient.request.body.Body;

/**
 * A factory for creating request bodies.
 * <p>
 * Implementations of this interface are responsible for creating {@link Body} instances
 * that provide the actual data to be sent in HTTP requests. The generator pattern allows
 * for creating multiple body instances with the same content, which is necessary for
 * scenarios like authentication challenges and redirects where the request needs to be resent.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Create a body generator from a byte array
 * BodyGenerator generator = new ByteArrayBodyGenerator("Hello World".getBytes());
 * Body body = generator.createBody();
 *
 * // Create a body generator from a file
 * BodyGenerator fileGenerator = new FileBodyGenerator(new File("data.txt"));
 * Body fileBody = fileGenerator.createBody();
 * }</pre>
 */
public interface BodyGenerator {

  /**
   * Creates a new instance of the request body to be read.
   * <p>
   * Each invocation of this method creates a fresh instance of the body, but the actual
   * contents of all these body instances is the same. This is necessary for scenarios where
   * the body needs to be resent, such as after an authentication challenge or redirect.
   * </p>
   *
   * @return the request body, never {@code null}
   */
  Body createBody();
}
