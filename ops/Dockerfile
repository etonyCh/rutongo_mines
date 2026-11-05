FROM gradle:8.13-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle :server:installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/server/build/install/server/ ./
EXPOSE 8080
ENTRYPOINT ["./bin/server"]
