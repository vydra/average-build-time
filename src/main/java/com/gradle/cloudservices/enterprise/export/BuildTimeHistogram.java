package com.gradle.cloudservices.enterprise.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.reactivex.netty.protocol.http.client.HttpClient;
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

import static java.time.Instant.now;

/* @Author Russel Hart rus@gradle.com */

public final class BuildTimeHistogram {

    private static final SocketAddress GRADLE_ENTERPRISE_SERVER = new InetSocketAddress(
            System.getProperty("server"), Integer.parseInt( System.getProperty("port","443")) );

    private static final HttpClient<ByteBuf, ByteBuf> HTTP_CLIENT = HttpClient.newClient(GRADLE_ENTERPRISE_SERVER).unsafeSecure();
    private static final int THROTTLE = 5;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // A Histogram covering the range from 1 nsec to 1 hour with 3 decimal point resolution:
    private static Histogram histogram = new Histogram(3600000000000L, 3);

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


        buildStream(since)
                .doOnSubscribe(() -> System.out.println("Streaming builds..."))
                .map(BuildTimeHistogram::parse)
                .map(json -> json.get("buildId").asText())
                .flatMap(buildId -> buildEventStream(buildId)
                                .doOnSubscribe(() -> System.out.println("Streaming events for : " + buildId))
                                .filter(serverSentEvent -> serverSentEvent.getEventTypeAsString().equals("BuildEvent"))
                                .map(BuildTimeHistogram::parse)
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
                                })
                        , THROTTLE
                )
                .filter(buildInfo -> filterByTag(buildInfo))
                .filter(buildInfo -> filterByCustomeValue(buildInfo))
                .filter(buildInfo -> filterSuccess(buildInfo))
                .map(BuildInfo::duration)
                .toBlocking()
                .subscribe(
                        d -> histogram.recordValue(d.toMillis()),
                        Throwable::printStackTrace,
                        () -> histogram.outputPercentileDistribution(System.out, 1000.0)
                );
    }

    static boolean filterSuccess(BuildInfo buildInfo) {
        boolean DO_NOT_FILTER = true;
        String successProp = System.getProperty("success");
        //if(successProp==null) return buildInfo.success;
        return ( successProp==null || ! successProp.equals("true")) ? DO_NOT_FILTER : buildInfo.success ;
    }

    static boolean filterByTag(BuildInfo buildInfo) {
        String tagName = System.getProperty("tag");
        if(tagName==null) return true;
        return buildInfo.customTags.contains(tagName);
    }

    static boolean filterByCustomeValue(BuildInfo buildInfo) {
        String custValueFilterSpec = System.getProperty("customValue");
        if(custValueFilterSpec==null) return true;

        String[] _res = custValueFilterSpec.split(":");
        String customValueKey = _res[0];
        String customValueValue = _res[1];
        return buildInfo.customValues.getOrDefault(customValueKey, "").equals(customValueValue);
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
        return HTTP_CLIENT
                .createGet("/build-export/v1/builds/since/" + String.valueOf(since.toEpochMilli()))
                .addHeader("Authorization", "Basic ZG90Y29tLWRldjpwNSZZS2pUNHY0TEthZmxOZTJ5Kk5LVTA4Tm4wQFg=")
                .flatMap(HttpClientResponse::getContentAsServerSentEvents);
    }

    private static Observable<ServerSentEvent> buildEventStream(String buildId) {
        return HTTP_CLIENT
                .createGet("/build-export/v1/build/" + buildId + "/events")
                .addHeader("Authorization", "Basic ZG90Y29tLWRldjpwNSZZS2pUNHY0TEthZmxOZTJ5Kk5LVTA4Tm4wQFg=")
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
