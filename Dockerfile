# --- Build stage ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
ENV MAVEN_OPTS="-Xmx384m"
COPY pom.xml .
COPY src ./src
RUN mvn -B clean package -DskipTests

# --- Run stage ---
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/target/url-shortener.jar app.jar
ENV PORT=10000
EXPOSE 10000
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-Xmx384m", "-jar", "app.jar"]
