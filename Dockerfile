# 构建：docker build -t devvault-server .
FROM gradle:8.14.3-jdk17 AS build
WORKDIR /src
COPY . .
RUN gradle :app:installDist --no-daemon --console=plain

FROM eclipse-temurin:17-jre
RUN useradd --system --no-create-home devvault
WORKDIR /app
COPY --from=build /src/app/build/install/devvault-server /app
USER devvault
EXPOSE 8080
ENTRYPOINT ["/app/bin/devvault-server"]
