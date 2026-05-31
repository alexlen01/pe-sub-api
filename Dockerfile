FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apt-get update && apt-get install -y maven && mvn -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/pe-sub-api-0.1.0.jar app.jar
EXPOSE 3001
ENTRYPOINT ["java", "-jar", "app.jar"]
