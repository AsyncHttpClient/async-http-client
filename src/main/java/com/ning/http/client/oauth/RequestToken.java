package com.ning.http.client.oauth;

/**
 * Value class used for OAuth tokens (request secret, access secret);
 * simple container with two parts, public id part ("key") and
 * confidential ("secret") part.
 */
public class RequestToken
{
    private final String key;
    private final String secret;

    public RequestToken(String key, String token)
    {
        this.key = key;
        this.secret = token;
    }

    public String getKey() { return key; }
    public String getSecret() { return secret; }

    @Override public String toString()
    {
        StringBuilder sb = new StringBuilder("{ key=");
        appendValue(sb, key);
        sb.append(", secret=");
        appendValue(sb, secret);
        sb.append("}");
        return sb.toString();
    }
    
    private void appendValue(StringBuilder sb, String value)
    {
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"');
            sb.append(value);
            sb.append('"');
        }
    }

    @Override public int hashCode() {
        return key.hashCode() + secret.hashCode();
    }
    
    @Override public boolean equals(Object o)
    {
        if (o == this) return true;
        if (o == null || o.getClass() != getClass()) return false;
        RequestToken other = (RequestToken) o;
        return key.equals(other.key) && secret.equals(other.secret);
    }
}
