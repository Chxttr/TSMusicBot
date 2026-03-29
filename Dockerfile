FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update && \
    apt-get install -y python3 python3-pip ffmpeg curl unzip && \
    pip3 install --break-system-packages yt-dlp yt-dlp-ejs && \
    curl -fsSL https://deno.land/install.sh | sh && \
    ln -s /root/.deno/bin/deno /usr/local/bin/deno && \
    rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/tsbot-backend-0.0.1-SNAPSHOT.jar app.jar

RUN mkdir -p /app/config /app/data /app/logs

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.config.additional-location=file:/app/config/"]