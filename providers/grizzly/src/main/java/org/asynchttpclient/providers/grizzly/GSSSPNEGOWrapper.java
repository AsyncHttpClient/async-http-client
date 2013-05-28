package org.asynchttpclient.providers.grizzly;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;

import org.asynchttpclient.util.Base64;
import org.slf4j.LoggerFactory;

public class GSSSPNEGOWrapper {
    private final static org.slf4j.Logger LOGGER = LoggerFactory.getLogger(GSSSPNEGOWrapper.class);
    private static final String KERBEROS_OID = "1.2.840.113554.1.2.2";

    static GSSManager getManager() {
        return GSSManager.getInstance();
    }

    static byte[] generateGSSToken(
            final byte[] input, final Oid oid, final String authServer) throws GSSException {
        byte[] token = input;
        if (token == null) {
            token = new byte[0];
        }
        GSSManager manager = getManager();
        GSSName serverName = manager.createName("HTTP@" + authServer, GSSName.NT_HOSTBASED_SERVICE);
        GSSContext gssContext = manager.createContext(
                serverName.canonicalize(oid), oid, null, GSSContext.DEFAULT_LIFETIME);
        gssContext.requestMutualAuth(true);
        gssContext.requestCredDeleg(true);
        return gssContext.initSecContext(token, 0, token.length);
    }

    public static String generateToken(String authServer) {
        String returnVal = "";
        Oid oid;
        try {
            oid = new Oid(KERBEROS_OID);
            byte[] token = GSSSPNEGOWrapper.generateGSSToken(null, oid, authServer);
            returnVal = Base64.encode(token);
        } catch (GSSException e) {
            LOGGER.warn(e.toString(), e);
        }

        return returnVal;
    }
}
