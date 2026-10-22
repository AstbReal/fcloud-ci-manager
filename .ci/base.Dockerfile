FROM node:20-alpine

RUN apk add --no-cache openjdk17 nginx curl su-exec postgresql \
 && rm -rf /var/cache/apk/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"