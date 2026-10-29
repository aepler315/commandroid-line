# syntax=docker/dockerfile:1
# Build context: the prepared termux-packages checkout's scripts/ directory.
# Derived from scripts/Dockerfile at 5a6d1c1eb868795dce83a6c269387b9f82d21805.
# The Ubuntu package index is live; record the resulting image ID for releases.
FROM ubuntu:24.04
ENV LANG=en_US.UTF-8
COPY llvm-snapshot.gpg.key properties.sh setup-android-sdk.sh setup-cgct.sh setup-ubuntu.sh /tmp/termux-packages/scripts/
COPY build/termux_download.sh /tmp/termux-packages/scripts/build/

# The session certificate exists only during this RUN and is removed before the
# layer is committed. Never put a proxy certificate or credential in COPY.
RUN --mount=type=secret,id=proxy_ca,target=/run/proxy-ca.crt \
    apt-get update && apt-get install -yq ca-certificates && \
    if [ -s /run/proxy-ca.crt ]; then \
      cp /run/proxy-ca.crt /usr/local/share/ca-certificates/neoterm-session-proxy.crt; \
      update-ca-certificates; \
    fi && \
    apt-get -yq upgrade && \
    apt-get install -yq sudo lsb-release software-properties-common && \
    userdel ubuntu && useradd -u 1001 -U -m -s /bin/bash builder && \
    echo 'builder ALL=(root) NOPASSWD:ALL' > /etc/sudoers.d/builder && \
    chmod 0440 /etc/sudoers.d/builder && \
    chmod a+rx /tmp/termux-packages/scripts/*.sh /tmp/termux-packages/scripts/build/termux_download.sh && \
    su --whitelist-environment=HTTP_PROXY,HTTPS_PROXY,NO_PROXY,http_proxy,https_proxy,no_proxy - builder -c \
      "TERMUX_PKGS__BUILD__IS_DOCKER_BUILD=true /tmp/termux-packages/scripts/setup-ubuntu.sh" && \
    su --whitelist-environment=HTTP_PROXY,HTTPS_PROXY,NO_PROXY,http_proxy,https_proxy,no_proxy - builder -c \
      "TERMUX_PKGS__BUILD__IS_DOCKER_BUILD=true /tmp/termux-packages/scripts/setup-android-sdk.sh" && \
    apt-get remove -yq --autoremove lsb-release software-properties-common && \
    apt-get clean && rm -rf /var/lib/apt/lists/* && \
    rm -f /usr/local/share/ca-certificates/neoterm-session-proxy.crt && update-ca-certificates

USER builder:builder
WORKDIR /home/builder/termux-packages
