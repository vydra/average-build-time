package com.gradle.cloudservices.enterprise.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.reactivex.netty.protocol.http.client.HttpClient;
import io.reactivex.netty.protocol.http.client.HttpClientResponse;
import io.reactivex.netty.protocol.http.sse.ServerSentEvent;
import rx.Observable;
import  rx.observables.MathObservable;
import rx.exceptions.Exceptions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.time.Instant;


import static java.time.Instant.now;

public final class ComputeAverageBuildTime {

    private static final SocketAddress GRADLE_ENTERPRISE_SERVER = new InetSocketAddress(
            System.getProperty("server"), Integer.parseInt( System.getProperty("port","443")) );

    private static final HttpClient<ByteBuf, ByteBuf> HTTP_CLIENT = HttpClient.newClient(GRADLE_ENTERPRISE_SERVER).unsafeSecure();
    private static final int THROTTLE = 5;
    private static final ObjectMapper MAPPER = new ObjectMapper();


    public static void main(String[] args) throws Exception {

        String hoursStr = System.getProperty("hours");

        Instant since;
        // default to 24hs
        if(hoursStr == null) {
            since = now().minus(Duration.ofHours( Integer.parseInt("24")));
        }
        else if(hoursStr.equals("all")) {
            since = Instant.EPOCH;
            System.out.println("Calculating for all stored build scans");
        } else {
           since = now().minus(Duration.ofHours( Integer.parseInt(hoursStr)));
        }

        MathObservable.from(
            buildStream(since)
                    .doOnSubscribe(() -> System.out.println("Streaming builds..."))
                    .map(ComputeAverageBuildTime::parse)
                    .map(json -> json.get("buildId").asText())
                    .flatMap(buildId -> buildEventStream(buildId)
                            .doOnSubscribe(() -> System.out.println("Streaming events for : " + buildId))
                            .filter(serverSentEvent -> serverSentEvent.getEventTypeAsString().equals("BuildEvent"))
                            .map(ComputeAverageBuildTime::parse)
                            .map(json -> new BuildEventInfo(json))
                            .filter(info -> (info.type.equals("BuildStarted") || info.type.equals("BuildFinished") ) )
                            // assumes we have one 'BuildStarted' and one 'BuildFinished' event in stream
                            .reduce(0L, (a,b) -> Math.abs(a - b.timestamp))
                    ,
                    THROTTLE
                    )).averageLong(millis -> millis / 1000)
                .map( time -> "\nAverage Build Time: " + time + " seconds")
                .toBlocking()
                .subscribe(System.out::println);
            }


    static class BuildEventInfo {
        Long timestamp;
        String type;
        JsonNode failureNode;

        public BuildEventInfo(JsonNode json ) {
            this.timestamp = json.get("timestamp").asLong();
            this.type = json.get("type").get("eventType").asText();
            JsonNode dataNode = json.get("data");
            this.failureNode = dataNode != null ? dataNode.get("failure") : null;

        }

        public boolean isFailure() {
            return false;
        }
    }



    private static Observable<ServerSentEvent> buildStream(Instant since) {
        return HTTP_CLIENT
            .createGet("/build-export/v1/builds/since/" + String.valueOf(since.toEpochMilli()))
            .flatMap(HttpClientResponse::getContentAsServerSentEvents);
    }

    private static Observable<ServerSentEvent> buildEventStream(String buildId) {
        return HTTP_CLIENT
            .createGet("/build-export/v1/build/" + buildId + "/events")
            .flatMap(HttpClientResponse::getContentAsServerSentEvents);
    }

    private static JsonNode parse(ServerSentEvent serverSentEvent) {
        try {
            return MAPPER.readTree(serverSentEvent.contentAsString());
        } catch (IOException e) {
            throw Exceptions.propagate(e);
        } finally {
            boolean deallocated = serverSentEvent.release();
            assert deallocated;
        }
    }



}
