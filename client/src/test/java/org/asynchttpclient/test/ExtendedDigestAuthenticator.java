package org.asynchttpclient.test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Pure Java DigestAuthenticator for testing MD5, SHA-256, and SHA-512-256.
 */
public class ExtendedDigestAuthenticator {
    private final String advertisedAlgorithm;

    public ExtendedDigestAuthenticator() {
        this(null);
    }

    public ExtendedDigestAuthenticator(String advertisedAlgorithm) {
        this.advertisedAlgorithm = advertisedAlgorithm;
    }

    public String getAdvertisedAlgorithm() {
        return findAlgorithm(advertisedAlgorithm);
    }

    public static String findAlgorithm(String algorithm) {
        if (algorithm == null || "MD5".equalsIgnoreCase(algorithm) || "MD5-sess".equalsIgnoreCase(algorithm)) {
            return "MD5";
        } else if ("SHA-256".equalsIgnoreCase(algorithm) || "SHA-256-sess".equalsIgnoreCase(algorithm)) {
            return "SHA-256";
        } else if ("SHA-512-256".equalsIgnoreCase(algorithm) || "SHA-512-256-sess".equalsIgnoreCase(algorithm)) {
            return "SHA-512-256";
        } else {
            return null;
        }
    }

    public static MessageDigest getMessageDigest(String algorithm) throws NoSuchAlgorithmException {
        if (algorithm == null || "MD5".equalsIgnoreCase(algorithm) || "MD5-sess".equalsIgnoreCase(algorithm)) {
            return MessageDigest.getInstance("MD5");
        } else if ("SHA-256".equalsIgnoreCase(algorithm) || "SHA-256-sess".equalsIgnoreCase(algorithm)) {
            return MessageDigest.getInstance("SHA-256");
        } else if ("SHA-512-256".equalsIgnoreCase(algorithm) || "SHA-512-256-sess".equalsIgnoreCase(algorithm)) {
            return MessageDigest.getInstance("SHA-512/256");
        } else {
            throw new NoSuchAlgorithmException("Unsupported digest algorithm: " + algorithm);
        }
    }

    public static String newNonce() {
        byte[] nonceBytes = new byte[16];
        new Random().nextBytes(nonceBytes);
        return Base64.getEncoder().encodeToString(nonceBytes);
    }

    public String createAuthenticateHeader(String realm, String nonce, boolean stale) {
        StringBuilder header = new StringBuilder(128);
        header.append("Digest realm=\"").append(realm).append('"');
        header.append(", nonce=\"").append(nonce).append('"');
        String algorithm = getAdvertisedAlgorithm();
        if (algorithm != null) {
            header.append(", algorithm=").append(algorithm);
        }
        header.append(", qop=\"auth\"");
        if (stale) {
            header.append(", stale=true");
        }
        return header.toString();
    }

    /**
     * Validate a Digest response from the client.
     * @param method HTTP method
     * @param credentials The Authorization header value (without "Digest ")
     * @param password The user's password
     * @return true if valid, false otherwise
     */
    public static boolean validateDigest(String method, String credentials, String password) {
        Map<String, String> params = parseCredentials(credentials);
        String username = params.get("username");
        String realm = params.get("realm");
        String nonce = params.get("nonce");
        String uri = params.get("uri");
        String response = params.get("response");
        String qop = params.get("qop");
        String nc = params.get("nc");
        String cnonce = params.get("cnonce");
        String algorithm = findAlgorithm(params.get("algorithm"));

        if (algorithm == null) {
            algorithm = "MD5";
        }

        try {
            MessageDigest md = getMessageDigest(algorithm);
            String a1 = username + ':' + realm + ':' + password;
            byte[] ha1 = md.digest(a1.getBytes(StandardCharsets.ISO_8859_1));

            String ha1Hex = toHexString(ha1);
            String a2 = method + ':' + uri;
            byte[] ha2 = md.digest(a2.getBytes(StandardCharsets.ISO_8859_1));

            String ha2Hex = toHexString(ha2);
            String kd;
            if (qop != null && !qop.isEmpty()) {
                kd = ha1Hex + ':' + nonce + ':' + nc + ':' + cnonce + ':' + qop + ':' + ha2Hex;
            } else {
                kd = ha1Hex + ':' + nonce + ':' + ha2Hex;
            }

            String expectedResponse = toHexString(md.digest(kd.getBytes(StandardCharsets.ISO_8859_1)));
            return expectedResponse.equalsIgnoreCase(response);
        } catch (Exception e) {
            return false;
        }
    }

    public static Map<String, String> parseCredentials(String credentials) {
        Map<String, String> map = new HashMap<>();
        String[] parts = credentials.split(",");
        for (String part : parts) {
            int idx = part.indexOf('=');
            if (idx > 0) {
                String key = part.substring(0, idx).trim();
                String value = part.substring(idx + 1).trim();
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                map.put(key, value);
            }
        }
        return map;
    }

    private static String toHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
