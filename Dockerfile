# ==========================================
# STAGE 1: Build Frontend (Angular)
# ==========================================
FROM node:20-alpine AS frontend-build
WORKDIR /app-frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ==========================================
# STAGE 2: Build Backend (Spring Boot)
# ==========================================
FROM gradle:9.3-jdk21 AS backend-build
WORKDIR /app-backend
# Copy source code
COPY backend/ ./

# Copy built frontend assets from Stage 1 into Spring Boot's static resources
COPY --from=frontend-build /app-frontend/dist/frontend/browser ./src/main/resources/static

# Build the JAR
# We add -x buildFrontend -x installFrontend to stop Gradle from looking for package.json
RUN gradle bootJar -x test -x buildFrontend -x installFrontend --no-daemon

# ==========================================
# STAGE 3: Final Runtime Image
# ==========================================
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y \
    perl \
    make \
    gcc \
    unzip \
    wget \
    gnupg2 \
    libaio1 \
    postgresql-client \
    cpanminus \
    libdbi-perl \
    libdbd-pg-perl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/oracle
COPY docker-libs/instantclient-basic-linux.x64-*.zip .
COPY docker-libs/instantclient-sdk-linux.x64-*.zip .

RUN unzip instantclient-basic-linux.x64-*.zip && \
    unzip -o instantclient-sdk-linux.x64-*.zip && \
    rm *.zip && \
    mv instantclient_* active_client && \
    echo /opt/oracle/active_client > /etc/ld.so.conf.d/oracle-instantclient.conf && \
    ldconfig

ENV ORACLE_HOME=/opt/oracle/active_client
ENV LD_LIBRARY_PATH=$ORACLE_HOME
ENV PATH=$PATH:$ORACLE_HOME

RUN cpanm --notest DBD::Oracle

ENV ORA2PG_VERSION=24.1
RUN wget https://github.com/darold/ora2pg/archive/v${ORA2PG_VERSION}.tar.gz && \
    tar xzf v${ORA2PG_VERSION}.tar.gz && \
    cd ora2pg-${ORA2PG_VERSION} && \
    perl Makefile.PL && \
    make && \
    make install && \
    cd .. && \
    rm -rf ora2pg* v${ORA2PG_VERSION}.tar.gz

# RUN
WORKDIR /app
COPY --from=backend-build /app-backend/build/libs/*.jar app.jar
RUN mkdir -p /data/projects

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
