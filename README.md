# Calculates Average Build Time

## Parameters:

server - Gradle Enterprise server name (assumes https on port 443)

port - Gradle Enterprise server port. Defaults to 443

hours - how many hours to go back from now. Default is EPOCH, i.e. all builds scans in the system.

## Setup

To run this sample:

1. Open a terminal window.
2. Run `./gradlew run -Dserver=upur_server_name -Dhours=24` from the command line.

Sample output:
```
Streaming builds...
Streaming events for : sfak5xs2k6wrs
Streaming events for : 65gz34ahyclqq
Streaming events for : 26xugvjbpd2xq
Streaming events for : gu7u2rqrob3yk
Streaming events for : evxyb4demqbee
Streaming events for : mo7ajx6tmfluq
Streaming events for : 4mf2ogozwsasi
Streaming events for : lninbqrzb6tyc
Streaming events for : h6pakfgvlgkea
Streaming events for : c23sfb347tbmu
Streaming events for : 2ubdvp7vqukqy
Streaming events for : r2enfmhiiuqju
Streaming events for : irxifle46qkmw

Average Build Time: 10 seconds
```

[BuildCountByUser]: src/main/java/com/gradle/cloudservices/enterprise/export/BuildCountByUser.java
