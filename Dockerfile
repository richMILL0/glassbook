# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY exchange-core/pom.xml exchange-core/
COPY exchange-server/pom.xml exchange-server/
COPY exchange-bench/pom.xml exchange-bench/
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -pl exchange-server -am dependency:go-offline || true
COPY exchange-core exchange-core
COPY exchange-server exchange-server
COPY exchange-bench exchange-bench
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -pl exchange-server -am -DskipTests package \
 && cp exchange-server/target/exchange-server-*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 glassbook && mkdir -p /data && chown glassbook /data
USER glassbook
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
ENV ENGINE_DATA_DIR=/data/engine \
    JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -Xms512m -Xmx1g -XX:+ExitOnOutOfMemoryError"
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
