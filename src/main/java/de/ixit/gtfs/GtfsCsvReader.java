package de.ixit.gtfs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class GtfsCsvReader {
    private GtfsCsvReader() {
    }

    public static long read(InputStream inputStream, Consumer<Row> rowConsumer) throws IOException {
        return read(inputStream, rowConsumer, null);
    }

    public static long read(InputStream inputStream, Consumer<Row> rowConsumer, ProgressListener progressListener) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8), 1024 * 1024)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return 0;
            }

            List<String> headers = parseLine(stripBom(headerLine));
            Map<String, Integer> indexByName = new HashMap<>();
            for (int index = 0; index < headers.size(); index++) {
                indexByName.put(headers.get(index), index);
            }

            long count = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                rowConsumer.accept(new Row(parseLine(line), indexByName));
                count++;
                if (progressListener != null) {
                    progressListener.onRowsRead(count);
                }
            }
            return count;
        }
    }

    public static List<String> readHeaders(InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8),
                64 * 1024
        )) {
            String headerLine = reader.readLine();
            return headerLine == null ? List.of() : List.copyOf(parseLine(stripBom(headerLine)));
        }
    }

    private static String stripBom(String value) {
        if (!value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }

    static List<String> parseLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int index = 0; index < line.length(); index++) {
            char ch = line.charAt(index);
            if (ch == '"') {
                if (inQuotes && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values;
    }

    public record Row(List<String> values, Map<String, Integer> indexByName) {
        public String required(String name) {
            String value = optional(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing required GTFS field: " + name);
            }
            return value;
        }

        public String optional(String name) {
            Integer index = indexByName.get(name);
            if (index == null || index >= values.size()) {
                return null;
            }
            String value = values.get(index);
            return value == null || value.isEmpty() ? null : value;
        }

        public Integer optionalInt(String name) {
            String value = optional(name);
            return value == null ? null : Integer.parseInt(value);
        }

        public Double optionalDouble(String name) {
            String value = optional(name);
            return value == null ? null : Double.parseDouble(value);
        }
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onRowsRead(long rowsRead);
    }
}
