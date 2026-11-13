
# syntax=docker/dockerfile:1.7

FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /build

# Ограничиваем память JVM при сборке
ENV MAVEN_OPTS="-Xmx384m -XX:MaxMetaspaceSize=192m"

# Сначала зависимости
COPY pom.xml .

# Кэш Maven сохраняется между сборками
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B dependency:go-offline

# Затем исходники
COPY src ./src

# Сборка приложения
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B verify

# Runtime
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S studio \
    && adduser -S studio -G studio \
    && mkdir -p /app/data/uploads \
    && chown -R studio:studio /app

WORKDIR /app

COPY --from=build /build/target/overlay-1.0.0.jar app.jar

USER studio

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "app.jar"]
