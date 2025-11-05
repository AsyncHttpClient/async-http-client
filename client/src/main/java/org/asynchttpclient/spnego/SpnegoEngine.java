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
 * <p>
 * This class implements the SPNEGO authentication protocol used primarily for Kerberos-based
 * authentication in enterprise environments. SPNEGO allows HTTP clients to authenticate to
 * servers using GSS-API tokens, typically wrapping Kerberos tickets.
 * </p>
 * <p>
 * The implementation supports both SPNEGO (OID 1.3.6.1.5.5.2) and Kerberos v5
 * (OID 1.2.840.113554.1.2.2) mechanisms, with automatic fallback to Kerberos if SPNEGO
 * is not supported by the JRE.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Using default instance
 * SpnegoEngine engine = new SpnegoEngine();
 * String token = engine.generateToken("example.com");
 *
 * // Using configured instance with credentials
 * SpnegoEngine engine = SpnegoEngine.instance(
 *     "username",
 *     "password",
 *     null,                   // servicePrincipalName
 *     "EXAMPLE.COM",          // realmName
 *     true,                   // useCanonicalHostname
 *     null,                   // customLoginConfig
 *     "MyLoginContext"        // loginContextName
 * );
 * String token = engine.generateToken("server.example.com");
 * }</pre>
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

  /**
   * Creates a new SPNEGO engine with the specified configuration.
   *
   * @param username the username for authentication (can be null for default credentials)
   * @param password the password for authentication (can be null for default credentials)
   * @param servicePrincipalName the service principal name (can be null to use default HTTP@host)
   * @param realmName the Kerberos realm name (can be null)
   * @param useCanonicalHostname whether to use canonical hostname resolution
   * @param customLoginConfig custom login configuration map for Krb5LoginModule (can be null)
   * @param loginContextName the name of the login context (can be null)
   * @param spnegoGenerator optional SPNEGO token generator for wrapping Kerberos tickets (can be null)
   */
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

  /**
   * Creates a new SPNEGO engine with default configuration.
   * <p>
   * This constructor initializes the engine to use canonical hostname resolution
   * and default credentials from the system.
   * </p>
   */
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

  /**
   * Returns a cached SPNEGO engine instance for the given configuration.
   * <p>
   * This factory method maintains a cache of engine instances keyed by the configuration
   * parameters. If an engine with the same configuration already exists, it is returned;
   * otherwise, a new instance is created and cached.
   * </p>
   *
   * @param username the username for authentication (can be null for default credentials)
   * @param password the password for authentication (can be null for default credentials)
   * @param servicePrincipalName the service principal name (can be null to use default HTTP@host)
   * @param realmName the Kerberos realm name (can be null)
   * @param useCanonicalHostname whether to use canonical hostname resolution
   * @param customLoginConfig custom login configuration map for Krb5LoginModule (can be null)
   * @param loginContextName the name of the login context (can be null)
   * @return a cached or new SPNEGO engine instance
   */
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

  /**
   * Generates a SPNEGO authentication token for the specified host.
   * <p>
   * This method creates a GSS security context and generates a Base64-encoded authentication
   * token that can be used in an HTTP Authorization header. The method handles:
   * </p>
   * <ul>
   *   <li>Creating a GSS context with SPNEGO or Kerberos OID</li>
   *   <li>Performing Kerberos login if credentials are configured</li>
   *   <li>Initializing the security context with the server name</li>
   *   <li>Wrapping Kerberos tickets in SPNEGO format if needed</li>
   * </ul>
   * <p>
   * The method will attempt to use SPNEGO by default and fall back to Kerberos v5
   * if SPNEGO is not supported by the JRE (typically JRE 1.5 and earlier).
   * </p>
   *
   * @param host the target server hostname for which to generate the token
   * @return a Base64-encoded SPNEGO or Kerberos authentication token
   * @throws SpnegoEngineException if token generation fails due to invalid credentials,
   *         GSS-API errors, or login failures
   */
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

  /**
   * Constructs the complete service principal name (SPN) for the given host.
   * <p>
   * If a custom service principal name is configured, it is used directly. Otherwise,
   * the method constructs an SPN in the format "HTTP@hostname". If canonical hostname
   * resolution is enabled, the hostname is resolved to its canonical form.
   * </p>
   *
   * @param host the hostname for which to construct the SPN
   * @return the complete service principal name
   */
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

  /**
   * Returns the login configuration for Kerberos authentication.
   * <p>
   * If custom login configuration is provided, returns a configuration that uses
   * the Krb5LoginModule with the custom settings. Otherwise, returns null to use
   * the default system configuration.
   * </p>
   *
   * @return the login configuration, or null to use system defaults
   */
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
