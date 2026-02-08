FROM ubuntu:22.04

# Install Dante SOCKS server
RUN apt-get update && \
    apt-get install -y dante-server && \
    rm -rf /var/lib/apt/lists/*

# Copy dante configuration (danted uses /etc/danted.conf)
COPY sockd.conf /etc/danted.conf

# Expose SOCKS port
EXPOSE 1080

# Run dante server (binary is /usr/sbin/danted)
CMD ["/usr/sbin/danted"]
