# Calculates Average Build Time

## Parameters:

server - Gradle Enterprise server name (assumes https on port 443)

port - Gradle Enterprise server port. Defaults to 443

hours - how many hours to go back from now. Default is 24hours. Use 'all' for all builds scans in the system (Warning: maybe be slow)

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

# Histogram

## Parameters:

server - Gradle Enterprise server name (assumes https on port 443) (required)

port - Gradle Enterprise server port. Defaults to 443

hours - how many hours to go back from now. Default is 24hours. Use 'all' for all builds scans in the system (Warning: maybe be slow)

tag - tag to filter (optional)

customValue - custom value to filter (optional)

success - filter only successful builds (default is all builds)


To run histogram use: ./gradlew runHistogram -Dserver=ubuntu16 -Dhours=all -Dtag=LOCAL -DcustomValue="Git Branch Name:custom-values"  -Dsuccess=true

Sample output:
```  Value     Percentile TotalCount 1/(1-Percentile)
   
          5.975 0.000000000000          1           1.00
          5.975 0.100000000000          1           1.11
          5.975 0.200000000000          1           1.25
          6.327 0.300000000000          2           1.43
          6.327 0.400000000000          2           1.67
          6.327 0.500000000000          2           2.00
          6.507 0.550000000000          3           2.22
          6.507 0.600000000000          3           2.50
          6.507 0.650000000000          3           2.86
          6.507 0.700000000000          3           3.33
          6.507 0.750000000000          3           4.00
          8.231 0.775000000000          4           4.44
          8.231 1.000000000000          4
   #[Mean    =        6.759, StdDeviation   =        0.870]
   #[Max     =        8.231, Total count    =            4]
   #[Buckets =           32, SubBuckets     =         2048]```