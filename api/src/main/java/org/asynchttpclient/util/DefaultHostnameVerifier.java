/*
 * To the extent possible under law, Kevin Locke has waived all copyright and
 * related or neighboring rights to this work.
 * <p/>
 * A legal description of this waiver is available in <a href="https://gist.github.com/kevinoid/3829665">LICENSE.txt</a>
 */
package org.asynchttpclient.util;

import sun.security.util.HostnameChecker;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.security.auth.kerberos.KerberosPrincipal;
import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * Uses the internal HostnameChecker to verify the server's hostname matches with the
 * certificate.  This is a requirement for HTTPS, but the raw SSLEngine does not have
 * this functionality.  As such, it has to be added in manually.  For a more complete
 * description of hostname verification and why it's important,
 * please read
 * <a href="http://tersesystems.com/2014/03/23/fixing-hostname-verification/">Fixing
 * Hostname Verification</a>.
 * <p/>
 * This code is based on Kevin Locke's <a href="http://kevinlocke.name/bits/2012/10/03/ssl-certificate-verification-in-dispatch-and-asynchttpclient/">guide</a> .
 * <p/>

 */
public class DefaultHostnameVerifier implements HostnameVerifier {

    private HostnameVerifier extraHostnameVerifier;

    public DefaultHostnameVerifier() {
    }

    public DefaultHostnameVerifier(HostnameVerifier extraHostnameVerifier) {
        this.extraHostnameVerifier = extraHostnameVerifier;
    }

    private boolean hostnameMatches(String hostname, SSLSession session) {
        HostnameChecker checker =
                HostnameChecker.getInstance(HostnameChecker.TYPE_TLS);

        boolean validCertificate = false, validPrincipal = false;
        try {
            Certificate[] peerCertificates = session.getPeerCertificates();

            if (peerCertificates.length > 0 &&
                    peerCertificates[0] instanceof X509Certificate) {
                X509Certificate peerCertificate =
                        (X509Certificate) peerCertificates[0];

                try {
                    checker.match(hostname, peerCertificate);
                    // Certificate matches hostname
                    validCertificate = true;
                } catch (CertificateException ex) {
                    // Certificate does not match hostname
                }
            } else {
                // Peer does not have any certificates or they aren't X.509
            }
        } catch (SSLPeerUnverifiedException ex) {
            // Not using certificates for peers, try verifying the principal
            try {
                Principal peerPrincipal = session.getPeerPrincipal();
                if (peerPrincipal instanceof KerberosPrincipal) {
                    validPrincipal = HostnameChecker.match(hostname,
                            (KerberosPrincipal) peerPrincipal);
                } else {
                    // Can't verify principal, not Kerberos
                }
            } catch (SSLPeerUnverifiedException ex2) {
                // Can't verify principal, no principal
            }
        }

        return validCertificate || validPrincipal;
    }

    public boolean verify(String hostname, SSLSession session) {
        if (hostnameMatches(hostname, session)) {
            return true;
        } else {
            if (extraHostnameVerifier != null) {
                return extraHostnameVerifier.verify(hostname, session);
            } else {
                return false;
            }
        }
    }
}
