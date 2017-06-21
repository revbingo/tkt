FROM openjdk:8-jre-alpine

RUN mkdir -p /opt/tkt
COPY build/libs/tkt.jar /opt/tkt/tkt.jar
COPY data/instances.json /opt/tkt/instances.json

VOLUME /opt/tkt/db
VOLUME /opt/tkt/credentials

EXPOSE 4567

CMD ["java", "-jar", "/opt/tkt/tkt.jar", "-i", "/opt/tkt/instances.json", "--db", "/opt/tkt/db/repo", "--creds", "/opt/tkt/credentials"]
