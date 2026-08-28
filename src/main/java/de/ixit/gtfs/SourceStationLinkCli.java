package de.ixit.gtfs;

import java.nio.file.Path;

public final class SourceStationLinkCli {
    private SourceStationLinkCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 8 && args.length != 10) throw new IllegalArgumentException("Usage: --source-db source.sqlite --runtime-db ixit-runtime.sqlite --source-id VBB --output source-station-links.sqlite [--runtime-data-version version]");
        String sourceDb = null, runtimeDb = null, sourceId = null, output = null, runtimeDataVersion = null;
        for (int index = 0; index < args.length; index += 2) {
            switch (args[index]) {
                case "--source-db" -> sourceDb = args[index + 1];
                case "--runtime-db" -> runtimeDb = args[index + 1];
                case "--source-id" -> sourceId = args[index + 1];
                case "--output" -> output = args[index + 1];
                case "--runtime-data-version" -> runtimeDataVersion = args[index + 1];
                default -> throw new IllegalArgumentException("Unknown option: " + args[index]);
            }
        }
        SourceStationLinkBuilder.BuildResult result = new SourceStationLinkBuilder().build(Path.of(sourceDb), Path.of(runtimeDb), sourceId, Path.of(output), runtimeDataVersion);
        System.out.println("Source station links built stations=" + result.sourceStationCount() + " exactGlobalId=" + result.exactGlobalIdCount() + " exactStopCode=" + result.exactStopCodeCount() + " coordinateNameChecked=" + result.coordinateNameCheckedCount() + " coordinateUnique=" + result.coordinateUniqueCount() + " omitted=" + result.ambiguousOrUnmatchedCount() + " elapsedMs=" + result.elapsedMs() + " bytes=" + result.bytes());
    }
}
