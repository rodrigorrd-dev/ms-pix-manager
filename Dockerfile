# syntax=docker/dockerfile:1
# Build: Maven + JDK 21 (gera as classes Avro e o jar executável). Testes rodam no CI/`./mvn.sh verify`, não aqui.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -Dmaven.test.skip=true dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -Dmaven.test.skip=true -Djacoco.skip=true package \
 && cp target/ms-pix-manager-*.jar /build/app.jar

# Runtime: só JRE, usuário sem privilégios.
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S pix && adduser -S pix -G pix
WORKDIR /app
COPY --from=build /build/app.jar app.jar
USER pix
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
