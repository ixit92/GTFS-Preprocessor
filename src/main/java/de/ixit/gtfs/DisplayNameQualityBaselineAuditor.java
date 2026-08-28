package de.ixit.gtfs;

import de.ixit.gtfs.model.DisplayNameQualityFinding;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DisplayNameQualityBaselineAuditor {
    private DisplayNameQualityBaselineAuditor() {
    }

    public static DisplayNameQualityBaselineReport audit(Connection connection) throws SQLException {
        if (!hasTable(connection, "display_name_quality_findings")) {
            return new DisplayNameQualityBaselineReport(
                    DisplayNameQualityBaselineBuilder.BASELINE_VERSION,
                    false,
                    0,
                    0,
                    0,
                    0,
                    0,
                    java.util.Map.of(),
                    List.of("MISSING_DISPLAY_NAME_QUALITY_FINDINGS")
            );
        }

        List<DisplayNameQualityFinding> expected = DisplayNameQualityBaselineBuilder.build(connection).findings();
        List<DisplayNameQualityFinding> stored = new ArrayList<>();
        Set<String> expectedKeys = new HashSet<>();
        for (DisplayNameQualityFinding finding : expected) {
            expectedKeys.add(key(finding));
        }
        Set<String> storedKeys = new HashSet<>();
        long destructiveActions = 0;
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT area_id,
                            finding_type,
                            classification,
                            prefix,
                            public_stop_name,
                            public_city_name,
                            public_display_name,
                            action,
                            rationale
                     FROM display_name_quality_findings
                     ORDER BY area_id, finding_type
                     """)) {
            while (resultSet.next()) {
                DisplayNameQualityFinding finding = new DisplayNameQualityFinding(
                        resultSet.getString("area_id"),
                        resultSet.getString("finding_type"),
                        resultSet.getString("classification"),
                        resultSet.getString("prefix"),
                        resultSet.getString("public_stop_name"),
                        resultSet.getString("public_city_name"),
                        resultSet.getString("public_display_name"),
                        resultSet.getString("action"),
                        resultSet.getString("rationale")
                );
                stored.add(finding);
                storedKeys.add(key(finding));
                if (!DisplayNameQualityBaselineBuilder.ACTION_PRESERVE.equals(finding.action())) {
                    destructiveActions++;
                }
            }
        }
        Set<String> coverage = new HashSet<>(expectedKeys);
        coverage.removeAll(storedKeys);
        Set<String> unexpected = new HashSet<>(storedKeys);
        unexpected.removeAll(expectedKeys);
        long coverageGaps = coverage.size() + unexpected.size();
        return DisplayNameQualityBaselineBuilder.report(stored, coverageGaps, destructiveActions);
    }

    private static String key(DisplayNameQualityFinding finding) {
        return finding.areaId() + "\u0000" + finding.findingType();
    }

    private static boolean hasTable(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }
}
