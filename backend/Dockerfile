FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q verify
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S studio && adduser -S studio -G studio && mkdir -p /app/data/uploads && chown -R studio:studio /app
WORKDIR /app
COPY --from=build /build/target/overlay-1.0.0.jar app.jar
USER studio
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","app.jar"]
