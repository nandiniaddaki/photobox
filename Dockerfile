# ---- Stage 1: build the jar with Maven ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- Stage 2: small image that only runs the jar ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/photo-app.jar app.jar
# Free servers have 512 MB RAM, so keep Java's memory small
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k"
EXPOSE 8080
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
