FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apt-get update && apt-get install -y maven && mvn clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --system rtdwh && useradd --system --gid rtdwh --home-dir /app rtdwh
COPY --from=build /app/target/rtdwh-management-*.jar app.jar
RUN chown -R rtdwh:rtdwh /app
USER rtdwh
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
