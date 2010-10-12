package com.ning.http.client.oauth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.ning.http.util.UTF8Codec;

/**
 * Since cloning (of MAC instances)  is not necessarily supported on all platforms
 * (and specifically seems to fail on MacOS), let's wrap synchronization/reuse details here.
 * Assumption is that this is bit more efficient (even considering synchronization)
 * than locating and reconstructing instance each time.
 * In future we may want to use soft references and thread local instance.
 * 
 * @author tatu (tatu.saloranta@iki.fi)
 */
public class ThreadSafeHMAC
{
    private static final String HMAC_SHA1_ALGORITHM = "HmacSHA1";
    
    private final Mac mac;
    
    public ThreadSafeHMAC(ConsumerKey consumerAuth, RequestToken userAuth)
    {
        byte[] keyBytes = UTF8Codec.toUTF8(consumerAuth.getSecret() + "&" + userAuth.getSecret());
        SecretKeySpec signingKey = new SecretKeySpec(keyBytes, HMAC_SHA1_ALGORITHM);
        
        // Get an hmac_sha1 instance and initialize with the signing key
        try {
            mac = Mac.getInstance(HMAC_SHA1_ALGORITHM);
            mac.init(signingKey);        
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        
    }

    public synchronized byte[] digest(byte[] message)
    {
        mac.reset();
        return mac.doFinal(message);
    }
}
