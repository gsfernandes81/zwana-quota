# The Connect IQ simulator, headless. It links libwebkit2gtk-4.0, which
# Ubuntu 24.04 no longer ships, so 22.04. See README.md.
FROM ubuntu:22.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends \
    libwebkit2gtk-4.0-37 libsoup2.4-1 libsecret-1-0 libusb-1.0-0 libsm6 libxxf86vm1 \
    xvfb xdotool x11-apps imagemagick openjdk-17-jre-headless ca-certificates unzip
