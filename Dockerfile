# wego-async-http-client stage
FROM maven:3.6-jdk-11-slim

# Install build dependencies
RUN apt-get -qq update && \
  apt-get -qq install -y --no-install-recommends \
  build-essential \
  openssh-client \
  && rm -rf /var/lib/apt/lists/*

# Read repo args
ARG VERSION
ENV VERSION=${VERSION}

WORKDIR /app
COPY . .

RUN mvn compile -U \
  -Dmaven.test.skip=true \
  -Dgpg.skip \
  -Dproject.version=${VERSION}

CMD mvn deploy -U \
  -Dmaven.test.skip=true \
  -Dgpg.skip \
  -DdistMgmtReleasesUrl=${REPO_URL}/releases \
  -DdistMgmtSnapshotsUrl=${REPO_URL}/snapshots \
  -DAWS_DEFAULT_REGION=${AWS_DEFAULT_REGION} \
  -Dproject.version=${VERSION}
