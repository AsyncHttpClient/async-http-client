FROM ubuntu/squid:latest

# Install OpenSSL for certificate generation
RUN apt-get update && \
    apt-get install -y openssl && \
    rm -rf /var/lib/apt/lists/* && \
    mkdir -p /etc/squid/certs /var/log/squid && \
    chown -R proxy:proxy /var/log/squid /etc/squid/certs

# Generate self-signed certificate for localhost
RUN openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
    -keyout /etc/squid/certs/proxy.key \
    -out /etc/squid/certs/proxy.crt \
    -subj "/CN=localhost" && \
    cat /etc/squid/certs/proxy.key /etc/squid/certs/proxy.crt > /etc/squid/certs/proxy.pem && \
    chmod 600 /etc/squid/certs/proxy.key /etc/squid/certs/proxy.pem && \
    chmod 644 /etc/squid/certs/proxy.crt && \
    chown -R proxy:proxy /etc/squid/certs

# Copy squid configuration
COPY squid.conf /etc/squid/squid.conf
RUN chown proxy:proxy /etc/squid/squid.conf

EXPOSE 3128 3129

CMD ["squid", "-f", "/etc/squid/squid.conf", "-NYCd", "1"]