# syntax=docker.io/docker/dockerfile:1

# The API needs ffmpeg and ffprobe for probing, converting and tagging audio, so the image is built here rather than
# with Jib, which cannot add operating system packages to its base image.
FROM eclipse-temurin:25-jre AS extract
WORKDIR /app
COPY build/libs/app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg fonts-dejavu-core fontconfig \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=extract /app/extracted/dependencies/ ./
COPY --from=extract /app/extracted/spring-boot-loader/ ./
COPY --from=extract /app/extracted/snapshot-dependencies/ ./
COPY --from=extract /app/extracted/application/ ./
USER 1000:1000
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
