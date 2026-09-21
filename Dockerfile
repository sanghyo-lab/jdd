FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
COPY commerce-app ./commerce-app
COPY commerce-core ./commerce-core
COPY commerce-infra ./commerce-infra
COPY agent-app ./agent-app
COPY agent-core ./agent-core
COPY agent-infra ./agent-infra
COPY voc-app ./voc-app
COPY voc-core ./voc-core
COPY voc-infra ./voc-infra
COPY scenario-runner ./scenario-runner
RUN chmod +x gradlew
RUN ./gradlew --no-daemon :commerce-app:bootJar :agent-app:bootJar :voc-app:bootJar
ARG APP_MODULE
RUN cp ${APP_MODULE}/build/libs/app.jar /app.jar

FROM eclipse-temurin:21-jdk-jammy
RUN command -v curl && groupadd --gid 10001 jdd && useradd --uid 10001 --gid jdd --no-create-home jdd
WORKDIR /app
COPY --from=build /app.jar /app/app.jar
USER jdd
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
