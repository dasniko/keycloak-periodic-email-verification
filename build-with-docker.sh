#!/bin/bash
docker volume create --name maven-repo || true
docker run -it --rm \
  --name keycloak-extensions \
  -v maven-repo:/root/.m2 \
  -v "$(pwd)":/usr/src/project \
  -w /usr/src/project \
  maven:3-eclipse-temurin-21-alpine mvn clean package -DskipTests
