# syntax=docker/dockerfile:1

# ---- build stage: compile and package, with dependencies cached in their own layer
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/*.jar extract --layers --destination target/layers

# ---- runtime stage: JRE only, non-root, Spring Boot jar split into layers
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home ristomanager
WORKDIR /app
COPY --from=build /workspace/target/layers/dependencies/ ./
COPY --from=build /workspace/target/layers/spring-boot-loader/ ./
COPY --from=build /workspace/target/layers/snapshot-dependencies/ ./
COPY --from=build /workspace/target/layers/application/ ./
USER ristomanager
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "cloud-backend-0.1.0-SNAPSHOT.jar"]
