FROM eclipse-temurin:21-jdk@sha256:3e3c176ffed168beb42c607be9bc1639b466cf00261a0fb04425562c9d0c5c2b AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp -DskipTests dependency:go-offline
COPY src src
COPY docker/HealthCheck.java docker/HealthCheck.java
RUN ./mvnw -B -ntp -DskipTests package && javac -d healthcheck docker/HealthCheck.java

FROM eclipse-temurin:21-jre@sha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5
RUN groupadd --system rabbitadmin && useradd --system --gid rabbitadmin --home-dir /app rabbitadmin
WORKDIR /app
COPY --from=build --chown=rabbitadmin:rabbitadmin /build/target/ms-barriodigital-rabbitmq-admin-0.0.1-SNAPSHOT.jar app.jar
COPY --from=build --chown=rabbitadmin:rabbitadmin /build/healthcheck healthcheck
USER rabbitadmin
ENV SERVER_PORT=8088 SERVER_ADDRESS=0.0.0.0
EXPOSE 8088
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 CMD ["java", "-cp", "/app/healthcheck", "HealthCheck"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
