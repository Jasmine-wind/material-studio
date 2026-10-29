FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline
COPY src src
RUN ./mvnw -B package -DskipTests

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && useradd -m studio && mkdir /data && chown studio:studio /data
WORKDIR /app
COPY --from=build /app/target/material-studio-1.0.0.jar app.jar
USER studio
ENV STORAGE_PATH=/data/storage
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
