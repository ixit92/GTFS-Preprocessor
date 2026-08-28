package de.ixit.gtfs;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

final class GtfsCsvWriter {
    private GtfsCsvWriter() {
    }

    static void writeRow(Writer writer, List<String> values) throws IOException {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                writer.write(',');
            }
            writer.write(escape(values.get(index)));
        }
        writer.write('\n');
    }

    private static String escape(String value) {
        String safe = value == null ? "" : value;
        if (!safe.contains(",") && !safe.contains("\"")
                && !safe.contains("\n") && !safe.contains("\r")) {
            return safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }
}
