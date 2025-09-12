FROM ubuntu:22.04

# Install Dante SOCKS server
RUN apt-get update && \
    apt-get install -y dante-server && \
    rm -rf /var/lib/apt/lists/*

# Copy dante configuration
COPY sockd.conf /etc/sockd.conf

# Create run directory
RUN mkdir -p /var/run/sockd && \
    chmod 755 /var/run/sockd

# Expose SOCKS port
EXPOSE 1080

# Run dante server (sockd binary is in /usr/sbin)
CMD ["/usr/sbin/sockd", "-f", "/etc/sockd.conf", "-D"]
