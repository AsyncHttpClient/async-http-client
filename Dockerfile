# wego-async-http-client stage
FROM maven:3.6-jdk-11-slim

# Install build dependencies
RUN apt-get -qq update && \
  apt-get -qq install -y --no-install-recommends \
  build-essential \
  git \
  openssh-client \
  locales \
  wget \
  && rm -rf /var/lib/apt/lists/*

# Read repo args
ARG REVISION
ARG AWS_DEFAULT_REGION
ARG AWS_ACCESS_KEY_ID
ARG AWS_SECRET_ACCESS_KEY
ARG REPO_URL
ENV AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID}
ENV AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY}

WORKDIR /app
COPY . .

RUN mvn deploy -U \
  -Dmaven.test.skip=true \
  -Dgpg.skip \
  -DdistMgmtReleasesUrl=${REPO_URL}/releases \
  -DAWS_DEFAULT_REGION=${AWS_DEFAULT_REGION} \
  -Dproject.version=${REVISION}
