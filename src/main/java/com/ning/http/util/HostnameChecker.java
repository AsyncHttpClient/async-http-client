/*
 * Copyright (c) Will Sargent. All rights reserved.
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
package com.ning.http.util;

import java.security.Principal;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * Hostname checker interface.
 */
public interface HostnameChecker {

    public void match(String hostname, X509Certificate peerCertificate) throws CertificateException;

    public boolean match(String hostname, Principal principal);
}
