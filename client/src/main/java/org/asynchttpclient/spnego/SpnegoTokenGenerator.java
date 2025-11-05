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
/*
 * ====================================================================
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.asynchttpclient.spnego;

import java.io.IOException;

/**
 * Interface for generating SPNEGO tokens from Kerberos tickets.
 * <p>
 * Implementations of this interface transform Kerberos tickets into SPNEGO tokens
 * by wrapping them in the appropriate DER-encoded SPNEGO structure. This is necessary
 * for servers that only accept SPNEGO tokens but not raw Kerberos tickets.
 * </p>
 * <p>
 * Implementations of this interface are expected to be thread-safe.
 * </p>
 *
 * @since 4.1
 */
public interface SpnegoTokenGenerator {

  /**
   * Generates a SPNEGO DER-encoded object from a Kerberos ticket.
   * <p>
   * This method wraps the provided Kerberos ticket in a SPNEGO token structure
   * according to RFC 4178. The resulting token can be sent to servers that require
   * SPNEGO authentication.
   * </p>
   *
   * @param kerberosTicket the Kerberos ticket bytes to wrap
   * @return the SPNEGO DER-encoded token bytes
   * @throws IOException if an error occurs during token generation
   */
  byte[] generateSpnegoDERObject(byte[] kerberosTicket) throws IOException;
}
