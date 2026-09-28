FROM node:22.16.0-bookworm-slim AS frontend
WORKDIR /work/frontend
COPY frontend/package*.json ./
RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi
COPY frontend/ ./
RUN npm run test:unit && npm run build
FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /work
COPY pom.xml ./
COPY core core
COPY runtime-agentscope runtime-agentscope
COPY server server
COPY --from=frontend /work/frontend/dist/ server/src/main/resources/static/
RUN mvn -B verify
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN mkdir /app/.data && chown -R 10001:10001 /app
COPY --from=build /work/server/target/reimu-server-0.1.0-SNAPSHOT.jar /app/reimu.jar
USER 10001:10001
ENV BIND_ADDRESS=0.0.0.0
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/reimu.jar"]
