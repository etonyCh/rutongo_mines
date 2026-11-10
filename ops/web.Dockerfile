FROM gradle:8.13-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle :webApp:wasmJsBrowserDistribution --no-daemon

FROM nginx:1.27-alpine
COPY ops/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/webApp/build/dist/wasmJs/productionExecutable /usr/share/nginx/html
EXPOSE 80
