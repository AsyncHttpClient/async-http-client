package com.ning.http.client.providers.grizzly;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;

import com.ning.http.util.Base64;

public class GSSSPNEGOWrapper {

	private static final String KERBEROS_OID = "1.2.840.113554.1.2.2";
	
	static GSSManager getManager() {
        return GSSManager.getInstance();
    }
	
	static  byte[] generateGSSToken(
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
	
	public  static String generateToken(String authServer)
	{
		String  returnVal = "";
		Oid oid;
		try {
			oid = new Oid(KERBEROS_OID);
			byte[] token = GSSSPNEGOWrapper.generateGSSToken(null, oid, authServer);
			returnVal = new String(Base64.encode(token));
		} catch (GSSException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return returnVal;
	}
}
