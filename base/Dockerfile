# syntax=docker.io/docker/dockerfile:1

# Base image for Jib (see build.gradle), which can't add operating system packages itself:
# ffprobe and ffmpeg probe, convert and tag uploaded audio; the fonts are for the sample covers' titles.
# CI publishes it as bloomper/fgv-spexify-server-base:25 before each image build, so ffmpeg gets security updates.
FROM eclipse-temurin:25-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg fonts-dejavu-core fontconfig \
    && rm -rf /var/lib/apt/lists/*
