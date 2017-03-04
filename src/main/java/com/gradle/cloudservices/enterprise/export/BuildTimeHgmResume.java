package com.gradle.cloudservices.enterprise.export;




import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.reactivex.netty.protocol.http.client.HttpClient;
import io.reactivex.netty.protocol.http.client.HttpClientRequest;
import io.reactivex.netty.protocol.http.client.HttpClientResponse;
import io.reactivex.netty.protocol.http.sse.ServerSentEvent;
import org.HdrHistogram.Histogram;
import rx.Observable;
import rx.exceptions.Exceptions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static java.time.Instant.now;

/* @Author Russel Hart rus@gradle.com */

public final class BuildTimeHgmResume {

    private static final SocketAddress GRADLE_ENTERPRISE_SERVER = new InetSocketAddress("ubuntu16", 443);

    private static final HttpClient<ByteBuf, ByteBuf> HTTP_CLIENT = HttpClient.newClient(GRADLE_ENTERPRISE_SERVER).unsafeSecure();
    private static final int THROTTLE = 5;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // A Histogram covering the range from 1 nsec to 1 hour with 3 decimal point resolution:
    private static Histogram histogram = new Histogram(3600000000000L, 3);

    public static void main(String[] args) throws Exception {
        Instant since1Day = now().minus(Duration.ofHours(24));

        buildStream(since1Day)
                .map(BuildTimeHgmResume::parse)
                .map(json -> json.get("buildId").asText())
                .flatMap(buildId -> buildEventStream(buildId)
                                .filter(serverSentEvent -> serverSentEvent.getEventTypeAsString().equals("BuildEvent"))
                                .map(BuildTimeHgmResume::parse)
                                .reduce(new BuildInfo(), (buildInfo, json) -> {
                                    String eventType = json.get("type").get("eventType").asText();

                                    switch (eventType) {
                                        case "BuildStarted":
                                            buildInfo.startTime = Instant.ofEpochMilli(json.get("timestamp").asLong());
                                            break;
                                        case "BuildFinished":
                                            buildInfo.finishTime = Instant.ofEpochMilli(json.get("timestamp").asLong());
                                            buildInfo.success = !json.get("data").hasNonNull("failure");
                                            break;
                                        case "UserNamedValue":
                                            buildInfo.customValues.put(json.get("data").get("key").asText(), json.get("data").get("value").asText());
                                            break;
                                        case "UserTag":
                                            buildInfo.customTags.add(json.get("data").get("tag").asText());
                                            break;
                                    }

                                    return buildInfo;
                                }),
                        THROTTLE
                )
                //.filter(buildInfo -> buildInfo.customTags.contains("CI"))
                //.filter(buildInfo -> buildInfo.customValues.getOrDefault("CI Build Type", "").equals("Dotcom_BuildScans_Server_BuildTest_Publish"))
                .filter(buildInfo -> buildInfo.success)
                .map(BuildInfo::duration)
                .toBlocking()
                .subscribe(
                        d -> histogram.recordValue(d.toMillis()),
                        Throwable::printStackTrace,
                        () -> histogram.outputPercentileDistribution(System.out, 1000.0)
                );
    }

    static class BuildInfo {
        Instant startTime;
        Instant finishTime;
        boolean success;
        Map<String, String> customValues = new HashMap<>();
        List<String> customTags = new ArrayList<>();

        BuildInfo() { }

        Duration duration() {
            return Duration.between(startTime, finishTime);
        }
    }

    private static Observable<ServerSentEvent> buildStream(Instant since) {
        return buildStream(since, null);
    }

    private static Observable<ServerSentEvent> buildStream(Instant since, String lastEventId) {
        System.out.println("Build stream from " + lastEventId);
        AtomicReference<String> lastBuildId = new AtomicReference<>(null);

        HttpClientRequest<ByteBuf, ByteBuf> request = HTTP_CLIENT
                .createGet("/build-export/v1/builds/since/" + String.valueOf(since.toEpochMilli()))
                .setKeepAlive(true)
                .addHeader("Authorization", "Basic ZG90Y29tLWRldjpwNSZZS2pUNHY0TEthZmxOZTJ5Kk5LVTA4Tm4wQFg=");

        if (lastEventId != null) {
            request.addHeader("Last-Event-ID", lastEventId);
        }

        return request
                .flatMap(HttpClientResponse::getContentAsServerSentEvents)
                .doOnNext(serverSentEvent -> lastBuildId.set(serverSentEvent.getEventIdAsString()))
                .doOnSubscribe(() -> System.out.println("Streaming builds..."))
                .onErrorResumeNext(t -> {
                    System.out.println("Error streaming builds, resuming from " + lastBuildId.get() + "...");
                    return buildStream(since, lastBuildId.get());
                });
    }

    private static Observable<ServerSentEvent> buildEventStream(String buildId) {
        return buildEventStream(buildId, null);
    }

    private static Observable<ServerSentEvent> buildEventStream(String buildId, String lastEventId) {
        AtomicReference<String> lastBuildEventId = new AtomicReference<>(null);

        HttpClientRequest<ByteBuf, ByteBuf> request = HTTP_CLIENT
                .createGet("/build-export/v1/build/" + buildId + "/events")
                .setKeepAlive(true)
                .addHeader("Authorization", "Basic ZG90Y29tLWRldjpwNSZZS2pUNHY0TEthZmxOZTJ5Kk5LVTA4Tm4wQFg=");

        if (lastEventId != null) {
            request.addHeader("Last-Event-ID", lastEventId);
        }

        return request
                .flatMap(HttpClientResponse::getContentAsServerSentEvents)
                .doOnNext(serverSentEvent -> lastBuildEventId.set(serverSentEvent.getEventIdAsString()))
                .doOnSubscribe(() -> System.out.println("Streaming events for : " + buildId))
                .onErrorResumeNext(t -> {
                    System.out.println("Error streaming build events, resuming from " + lastBuildEventId.get() + "...");
                    return buildEventStream(buildId, lastBuildEventId.get());
                });
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

