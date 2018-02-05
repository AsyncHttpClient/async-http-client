/*
 * Copyright (c) 2018 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.netty.ssl;

import io.netty.handler.ssl.CipherSuiteFilter;

import java.util.List;
import java.util.Set;

// workaround for https://github.com/netty/netty/pull/7691
class IdentityCipherSuiteFilterWorkaround implements CipherSuiteFilter {
  static final IdentityCipherSuiteFilterWorkaround INSTANCE = new IdentityCipherSuiteFilterWorkaround();

  private IdentityCipherSuiteFilterWorkaround() { }

  @Override
  public String[] filterCipherSuites(Iterable<String> ciphers, List<String> defaultCiphers,
                                     Set<String> supportedCiphers) {
    if (ciphers == null) {
      return supportedCiphers.toArray(new String[supportedCiphers.size()]);
    } else {
      throw new UnsupportedOperationException();
    }
  }
}
