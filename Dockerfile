FROM docker.io/library/eclipse-temurin:26-jdk-jammy AS build
WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -DskipTests package

FROM docker.io/library/eclipse-temurin:26-jre-jammy
WORKDIR /app

RUN useradd --system --create-home --shell /usr/sbin/nologin stockselect
COPY --from=build /app/target/*.jar app.jar
USER stockselect

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
