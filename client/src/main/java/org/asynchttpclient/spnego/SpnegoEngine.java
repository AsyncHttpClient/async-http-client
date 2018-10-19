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
 */

package org.asynchttpclient.spnego;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import java.io.IOException;
import java.net.InetAddress;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * SPNEGO (Simple and Protected GSSAPI Negotiation Mechanism) authentication scheme.
 *
 * @since 4.1
 */
public class SpnegoEngine {

  private static final String SPNEGO_OID = "1.3.6.1.5.5.2";
  private static final String KERBEROS_OID = "1.2.840.113554.1.2.2";
  private static Map<String, SpnegoEngine> instances = new HashMap<>();
  private final Logger log = LoggerFactory.getLogger(getClass());
  private final SpnegoTokenGenerator spnegoGenerator;
  private final String username;
  private final String password;
  private final String servicePrincipalName;
  private final String realmName;
  private final boolean useCanonicalHostname;
  private final String loginContextName;
  private final Map<String, String> customLoginConfig;

  public SpnegoEngine(final String username,
                      final String password,
                      final String servicePrincipalName,
                      final String realmName,
                      final boolean useCanonicalHostname,
                      final Map<String, String> customLoginConfig,
                      final String loginContextName,
                      final SpnegoTokenGenerator spnegoGenerator) {
    this.username = username;
    this.password = password;
    this.servicePrincipalName = servicePrincipalName;
    this.realmName = realmName;
    this.useCanonicalHostname = useCanonicalHostname;
    this.customLoginConfig = customLoginConfig;
    this.spnegoGenerator = spnegoGenerator;
    this.loginContextName = loginContextName;
  }

  public SpnegoEngine() {
    this(null,
        null,
        null,
        null,
        true,
        null,
        null,
        null);
  }

  public static SpnegoEngine instance(final String username,
                                      final String password,
                                      final String servicePrincipalName,
                                      final String realmName,
                                      final boolean useCanonicalHostname,
                                      final Map<String, String> customLoginConfig,
                                      final String loginContextName) {
    String key = "";
    if (customLoginConfig != null && !customLoginConfig.isEmpty()) {
      StringBuilder customLoginConfigKeyValues = new StringBuilder();
      for (String loginConfigKey : customLoginConfig.keySet()) {
        customLoginConfigKeyValues.append(loginConfigKey).append("=")
          .append(customLoginConfig.get(loginConfigKey));
      }
      key = customLoginConfigKeyValues.toString();
    }
    if (username != null) {
      key += username;
    }
    if (loginContextName != null) {
      key += loginContextName;
    }
    if (!instances.containsKey(key)) {
      instances.put(key, new SpnegoEngine(username,
          password,
          servicePrincipalName,
          realmName,
          useCanonicalHostname,
          customLoginConfig,
          loginContextName,
          null));
    }
    return instances.get(key);
  }

  public String generateToken(String host) throws SpnegoEngineException {
    GSSContext gssContext = null;
    byte[] token = null; // base64 decoded challenge
    Oid negotiationOid;

    try {
      /*
       * Using the SPNEGO OID is the correct method. Kerberos v5 works for IIS but not JBoss. Unwrapping the initial token when using SPNEGO OID looks like what is described
       * here...
       *
       * http://msdn.microsoft.com/en-us/library/ms995330.aspx
       *
       * Another helpful URL...
       *
       * http://publib.boulder.ibm.com/infocenter/wasinfo/v7r0/index.jsp?topic=/com.ibm.websphere.express.doc/info/exp/ae/tsec_SPNEGO_token.html
       *
       * Unfortunately SPNEGO is JRE >=1.6.
       */

      // Try SPNEGO by default, fall back to Kerberos later if error
      negotiationOid = new Oid(SPNEGO_OID);

      boolean tryKerberos = false;
      String spn = getCompleteServicePrincipalName(host);
      try {
        GSSManager manager = GSSManager.getInstance();
        GSSName serverName = manager.createName(spn, GSSName.NT_HOSTBASED_SERVICE);
        GSSCredential myCred = null;
        if (username != null || loginContextName != null || (customLoginConfig != null && !customLoginConfig.isEmpty())) {
          String contextName = loginContextName;
          if (contextName == null) {
            contextName = "";
          }
          LoginContext loginContext = new LoginContext(contextName,
              null,
              getUsernamePasswordHandler(),
              getLoginConfiguration());
          loginContext.login();
          final Oid negotiationOidFinal = negotiationOid;
          final PrivilegedExceptionAction<GSSCredential> action = () -> manager.createCredential(null,
            GSSCredential.INDEFINITE_LIFETIME, negotiationOidFinal, GSSCredential.INITIATE_AND_ACCEPT);
          myCred = Subject.doAs(loginContext.getSubject(), action);
        }
        gssContext = manager.createContext(useCanonicalHostname ? serverName.canonicalize(negotiationOid) : serverName,
            negotiationOid,
            myCred,
            GSSContext.DEFAULT_LIFETIME);
        gssContext.requestMutualAuth(true);
        gssContext.requestCredDeleg(true);
      } catch (GSSException ex) {
        log.error("generateToken", ex);
        // BAD MECH means we are likely to be using 1.5, fall back to Kerberos MECH.
        // Rethrow any other exception.
        if (ex.getMajor() == GSSException.BAD_MECH) {
          log.debug("GSSException BAD_MECH, retry with Kerberos MECH");
          tryKerberos = true;
        } else {
          throw ex;
        }

      }
      if (tryKerberos) {
        /* Kerberos v5 GSS-API mechanism defined in RFC 1964. */
        log.debug("Using Kerberos MECH {}", KERBEROS_OID);
        negotiationOid = new Oid(KERBEROS_OID);
        GSSManager manager = GSSManager.getInstance();
        GSSName serverName = manager.createName(spn, GSSName.NT_HOSTBASED_SERVICE);
        gssContext = manager.createContext(serverName.canonicalize(negotiationOid), negotiationOid, null,
                GSSContext.DEFAULT_LIFETIME);
        gssContext.requestMutualAuth(true);
        gssContext.requestCredDeleg(true);
      }

      // TODO suspicious: this will always be null because no value has been assigned before. Assign directly?
      if (token == null) {
        token = new byte[0];
      }

      token = gssContext.initSecContext(token, 0, token.length);
      if (token == null) {
        throw new SpnegoEngineException("GSS security context initialization failed");
      }

      /*
       * IIS accepts Kerberos and SPNEGO tokens. Some other servers Jboss, Glassfish? seem to only accept SPNEGO. Below wraps Kerberos into SPNEGO token.
       */
      if (spnegoGenerator != null && negotiationOid.toString().equals(KERBEROS_OID)) {
        token = spnegoGenerator.generateSpnegoDERObject(token);
      }

      gssContext.dispose();

      String tokenstr = Base64.getEncoder().encodeToString(token);
      log.debug("Sending response '{}' back to the server", tokenstr);

      return tokenstr;
    } catch (GSSException gsse) {
      log.error("generateToken", gsse);
      if (gsse.getMajor() == GSSException.DEFECTIVE_CREDENTIAL || gsse.getMajor() == GSSException.CREDENTIALS_EXPIRED)
        throw new SpnegoEngineException(gsse.getMessage(), gsse);
      if (gsse.getMajor() == GSSException.NO_CRED)
        throw new SpnegoEngineException(gsse.getMessage(), gsse);
      if (gsse.getMajor() == GSSException.DEFECTIVE_TOKEN || gsse.getMajor() == GSSException.DUPLICATE_TOKEN
              || gsse.getMajor() == GSSException.OLD_TOKEN)
        throw new SpnegoEngineException(gsse.getMessage(), gsse);
      // other error
      throw new SpnegoEngineException(gsse.getMessage());
    } catch (IOException | LoginException | PrivilegedActionException ex) {
      throw new SpnegoEngineException(ex.getMessage());
    }
  }

  String getCompleteServicePrincipalName(String host) {
    String name;
    if (servicePrincipalName == null) {
      if (useCanonicalHostname) {
        host = getCanonicalHostname(host);
      }
      name = "HTTP@" + host;
    } else {
      name = servicePrincipalName;
      if (realmName != null && !name.contains("@")) {
        name += "@" + realmName;
      }
    }
    log.debug("Service Principal Name is {}", name);
    return name;
  }

  private String getCanonicalHostname(String hostname) {
    String canonicalHostname = hostname;
    try {
      InetAddress in = InetAddress.getByName(hostname);
      canonicalHostname = in.getCanonicalHostName();
      log.debug("Resolved hostname={} to canonicalHostname={}", hostname, canonicalHostname);
    } catch (Exception e) {
      log.warn("Unable to resolve canonical hostname", e);
    }
    return canonicalHostname;
  }

  private CallbackHandler getUsernamePasswordHandler() {
    if (username == null) {
      return null;
    }
    return new NamePasswordCallbackHandler(username, password);
  }

  public Configuration getLoginConfiguration() {
    if (customLoginConfig != null && !customLoginConfig.isEmpty()) {
      return new Configuration() {
        @Override
        public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
          return new AppConfigurationEntry[] {
              new AppConfigurationEntry("com.sun.security.auth.module.Krb5LoginModule",
                  AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                  customLoginConfig)};
        }
      };
    }
    return null;
  }
}
