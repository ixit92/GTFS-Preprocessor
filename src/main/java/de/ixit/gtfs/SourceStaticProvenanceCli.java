package de.ixit.gtfs;

import java.nio.file.Path;

public final class SourceStaticProvenanceCli {
    private SourceStaticProvenanceCli() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 8) throw new IllegalArgumentException("Usage: --source-db source.sqlite --source-zip feed.zip --source-id VBB --output provenance.sqlite");
        String sourceDb = null, sourceZip = null, sourceId = null, output = null;
        for (int index = 0; index < args.length; index += 2) { switch (args[index]) { case "--source-db" -> sourceDb = args[index + 1]; case "--source-zip" -> sourceZip = args[index + 1]; case "--source-id" -> sourceId = args[index + 1]; case "--output" -> output = args[index + 1]; default -> throw new IllegalArgumentException("Unknown option: " + args[index]); } }
        SourceStaticProvenanceBuilder.BuildResult result = new SourceStaticProvenanceBuilder().build(Path.of(sourceDb), Path.of(sourceZip), sourceId, Path.of(output));
        System.out.println("Source static provenance built stops=" + result.stops() + " trips=" + result.trips() + " stopTimes=" + result.stopTimes() + " elapsedMs=" + result.elapsedMs() + " bytes=" + result.bytes());
    }
}
