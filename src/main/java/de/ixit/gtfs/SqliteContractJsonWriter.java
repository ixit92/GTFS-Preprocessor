package de.ixit.gtfs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class SqliteContractJsonWriter {
    private SqliteContractJsonWriter() {
    }

    public static void write(
            Path outputPath,
            SqliteContractReport contractReport,
            RoutingCompatibilityAuditor.RoutingCompatibilityAuditReport routingAudit,
            TransferFootpathAuditReport transferFootpathAudit,
            RealFeedValidationReport realFeedValidation,
            AppReadySqliteReport appReadySqlite,
            ServiceDayModelReport serviceDayModel,
            List<String> warnings
    ) throws IOException {
        Path parent = outputPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(outputPath, toJson(contractReport, routingAudit, transferFootpathAudit, realFeedValidation, appReadySqlite, serviceDayModel, warnings), StandardCharsets.UTF_8);
    }

    private static String toJson(
            SqliteContractReport report,
            RoutingCompatibilityAuditor.RoutingCompatibilityAuditReport routingAudit,
            TransferFootpathAuditReport transferFootpathAudit,
            RealFeedValidationReport realFeedValidation,
            AppReadySqliteReport appReadySqlite,
            ServiceDayModelReport serviceDayModel,
            List<String> warnings
    ) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        appendProperty(json, "schema_version", report.schemaVersion(), true);
        appendProperty(json, "preprocessor_version", report.preprocessorVersion(), true);
        appendProperty(json, "contract_name", report.contractName(), true);
        appendProperty(json, "contract_version", report.contractVersion(), true);
        appendProperty(json, "time_model", report.timeModel(), true);
        appendStringMap(json, "metadata", report.metadata(), true, 2);
        json.append("  \"id_policies\": {\n");
        appendProperty(json, "stop_id_policy", report.stopIdPolicy(), true, 4);
        appendProperty(json, "area_id_policy", report.areaIdPolicy(), true, 4);
        appendProperty(json, "search_tokens_policy", report.searchTokensPolicy(), false, 4);
        json.append("  },\n");
        appendStringArray(json, "tables", report.tables(), true);
        appendStringArray(json, "indexes", report.indexes(), true);
        appendRowCounts(json, report.rowCounts(), true);
        appendHubProfileStats(json, report.hubProfileStats(), true);
        appendRouteAxisStats(json, report.routeAxisStats(), true);
        appendTransferRuleStats(json, report.transferRuleStats(), true);
        appendTransferEdgeStats(json, report.transferEdgeStats(), true);
        appendRoutingCompatibilityAudit(json, routingAudit, true);
        appendTransferFootpathAudit(json, transferFootpathAudit, true);
        appendRealFeedValidation(json, realFeedValidation, true);
        appendSqliteDiagnostics(json, realFeedValidation == null ? null : realFeedValidation.sqliteDiagnostics(), true);
        appendAppReadySqlite(json, appReadySqlite, true);
        appendServiceDayModel(json, serviceDayModel, true);
        appendStringArray(json, "warnings", warnings, false);
        json.append("}\n");
        return json.toString();
    }

    private static void appendServiceDayModel(StringBuilder json, ServiceDayModelReport report, boolean comma) {
        json.append("  \"service_day_model\": {\n");
        if (report == null) {
            appendProperty(json, "model_version", "", true, 4);
            json.append("    \"available\": false,\n");
            json.append("    \"pass\": false,\n");
            json.append("    \"services\": 0,\n");
            json.append("    \"samples\": []\n");
        } else {
            appendProperty(json, "model_version", report.modelVersion(), true, 4);
            json.append("    \"available\": ").append(report.available()).append(",\n");
            json.append("    \"pass\": ").append(report.pass()).append(",\n");
            json.append("    \"services\": ").append(report.serviceCount()).append(",\n");
            json.append("    \"trip_services\": ").append(report.tripServiceCount()).append(",\n");
            json.append("    \"base_calendar_services\": ").append(report.baseCalendarServiceCount()).append(",\n");
            json.append("    \"exception_services\": ").append(report.exceptionServiceCount()).append(",\n");
            json.append("    \"exception_only_services\": ").append(report.exceptionOnlyServiceCount()).append(",\n");
            json.append("    \"unresolved_trip_services\": ").append(report.unresolvedTripServiceCount()).append(",\n");
            json.append("    \"invalid_weekday_flags\": ").append(report.invalidWeekdayFlagCount()).append(",\n");
            json.append("    \"invalid_calendar_ranges\": ").append(report.invalidCalendarRangeCount()).append(",\n");
            json.append("    \"invalid_exception_dates\": ").append(report.invalidExceptionDateCount()).append(",\n");
            json.append("    \"invalid_exception_types\": ").append(report.invalidExceptionTypeCount()).append(",\n");
            json.append("    \"invalid_iana_timezone_services\": ").append(report.invalidIanaTimezoneServiceCount()).append(",\n");
            json.append("    \"unknown_timezone_trip_services\": ").append(report.unknownTimezoneTripServiceCount()).append(",\n");
            json.append("    \"multiple_timezone_trip_services\": ").append(report.multipleTimezoneTripServiceCount()).append(",\n");
            json.append("    \"overflow_stop_times\": ").append(report.overflowStopTimeCount()).append(",\n");
            json.append("    \"maximum_service_day_seconds\": ").append(report.maximumServiceDaySeconds()).append(",\n");
            appendLongMap(json, "service_timezones", report.timezoneCounts(), true, 4);
            json.append("    \"samples\": [");
            for (int index = 0; index < report.samples().size(); index++) {
                if (index > 0) {
                    json.append(", ");
                }
                json.append("\"").append(escape(report.samples().get(index))).append("\"");
            }
            json.append("]\n");
        }
        json.append("  }");
        if (comma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static void appendProperty(StringBuilder json, String key, String value, boolean comma) {
        appendProperty(json, key, value, comma, 2);
    }

    private static void appendProperty(StringBuilder json, String key, String value, boolean comma, int indent) {
        json.append(" ".repeat(indent))
                .append("\"")
                .append(escape(key))
                .append("\": \"")
                .append(escape(value))
                .append("\"");
        if (comma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static void appendStringArray(StringBuilder json, String key, List<String> values, boolean comma) {
        appendStringArray(json, key, values, comma, 2);
    }

    private static void appendStringArray(StringBuilder json, String key, List<String> values, boolean comma, int indent) {
        json.append(" ".repeat(indent)).append("\"").append(escape(key)).append("\": [");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(", ");
            }
            json.append("\"").append(escape(values.get(index))).append("\"");
        }
        json.append("]");
        if (comma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static void appendRowCounts(StringBuilder json, Map<String, Long> rowCounts, boolean comma) {
        appendLongMap(json, "row_counts", rowCounts, comma, 2);
    }

    private static void appendLongMap(StringBuilder json, String key, Map<String, Long> values, boolean comma, int indent) {
        json.append(" ".repeat(indent)).append("\"").append(escape(key)).append("\": {\n");
        int index = 0;
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            json.append(" ".repeat(indent + 2)).append("\"").append(escape(entry.getKey())).append("\": ").append(entry.getValue());
            if (++index < values.size()) {
                json.append(",");
            }
            json.append("\n");
        }
        json.append(" ".repeat(indent)).append("}");
        if (comma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static void appendHubProfileStats(StringBuilder json, HubProfileBuilder.HubProfileStats stats, boolean comma) {
        json.append("  \"hub_profiles\": {\n");
        if (stats == null) {
            json.append("    \"profile_count\": 0,\n");
            json.append("    \"level_counts\": {},\n");
            json.append("    \"main_station_candidates\": []\n");
        } else {
            json.append("    \"profile_count\": ").append(stats.profileCount()).append(",\n");
            json.append("    \"level_counts\": {\n");
            int levelIndex = 0;
            for (Map.Entry<String, Integer> entry : stats.levelCounts().entrySet()) {
                json.append("      \"").append(escape(entry.getKey())).append("\": ").append(entry.getValue());
                if (++levelIndex < stats.levelCounts().size()) {
                    json.append(",");
                }
                json.append("\n");
            }
            json.append("    },\n");
            json.append("    \"main_station_candidates\": [");
            for (int index = 0; index < stats.mainStationCandidates().size(); index++) {
                if (index > 0) {
                    json.append(", ");
                }
                json.append("\"").append(escape(stats.mainStationCandidates().get(index).areaId())).append("\"");
            }
            json.append("]\n");
        }
        json.append("  }");
        if (comma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static void appendRouteAxisStats(StringBuilder json, RouteAxisBuilder.RouteAxisStats stats, boolean comma) {
        json.append("  \"route_axes\": {\n");
        if (stats == null) {
            json.append("    \"axis_count\": 0,\n");
            json.append("    \"axis_stop_count\": 0,\n");
            json.append("    \"top_axes\": []\n");
        } else {
            json.append("    \"axis_count\": ").append(stats.axisCount()).append(",\n");
            json.append("    \"axis_stop_count\": ").append(stats.axisStopCount()).append(",\n");
            json.append("    \"top_axes\": [");
            for (int index = 0; index < stats.topAxesByTripCount().size(); index++) {
                if (index > 0) {
                    json.append(", ");
                }
                json.append("\"").append(escape(stats.topAxesByTripCount().get(index).axisId())).append("\"");
            }
            json.append("],\n");
            appendRoutesWithMostAxes(json, stats);
        }
        json.append("  }");
        if (comma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static void appendTransferRuleStats(StringBuilder json, TransferRuleBuilder.TransferRuleStats stats, boolean comma) {
        json.append("  \"transfer_rules\": {\n");
        if (stats == null) {
            json.append("    \"rule_count\": 0,\n");
            json.append("    \"source_counts\": {},\n");
            json.append("    \"confidence_counts\": {}\n");
        } else {
            json.append("    \"rule_count\": ").append(stats.ruleCount()).append(",\n");
            appendIntegerMap(json, "source_counts", stats.sourceCounts(), true, 4);
            appendIntegerMap(json, "confidence_counts", stats.confidenceCounts(), true, 4);
            appendIntegerMap(json, "semantic_counts", stats.semanticCounts(), true, 4);
            json.append("    \"gtfs_transfers_mapped\": ").append(stats.gtfsTransfersMapped()).append(",\n");
            json.append("    \"gtfs_transfers_unmapped\": ").append(stats.gtfsTransfersUnmapped()).append(",\n");
            json.append("    \"same_stop_area_rules\": ").append(stats.sameStopAreaRules()).append(",\n");
            json.append("    \"scoped_gtfs_transfers\": ").append(stats.scopedGtfsTransfers()).append(",\n");
            json.append("    \"pedestrian_candidate_transfers\": ").append(stats.pedestrianCandidateTransfers()).append(",\n");
            json.append("    \"excluded_non_pedestrian_transfers\": ").append(stats.excludedNonPedestrianTransfers()).append("\n");
        }
        json.append("  }");
        if (comma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static void appendTransferEdgeStats(StringBuilder json, TransferEdgeBuilder.TransferEdgeStats stats, boolean comma) {
        json.append("  \"transfer_edges\": {\n");
        if (stats == null) {
            json.append("    \"edge_count\": 0,\n");
            json.append("    \"source_counts\": {},\n");
            json.append("    \"quality_counts\": {},\n");
            json.append("    \"distance_meters\": {},\n");
            json.append("    \"min_transfer_minutes\": {}\n");
        } else {
            json.append("    \"edge_count\": ").append(stats.edgeCount()).append(",\n");
            appendIntegerMap(json, "source_counts", stats.sourceCounts(), true, 4);
            appendIntegerMap(json, "quality_counts", stats.qualityCounts(), true, 4);
            json.append("    \"distance_meters\": {")
                    .append("\"min\": ").append(stats.minDistanceMeters() == null ? 0 : stats.minDistanceMeters())
                    .append(", \"max\": ").append(stats.maxDistanceMeters() == null ? 0 : stats.maxDistanceMeters())
                    .append(", \"avg\": ").append(formatDouble(stats.averageDistanceMeters()))
                    .append("},\n");
            json.append("    \"min_transfer_minutes\": {")
                    .append("\"min\": ").append(stats.minTransferMinutes())
                    .append(", \"max\": ").append(stats.maxTransferMinutes())
                    .append(", \"avg\": ").append(formatDouble(stats.averageTransferMinutes()))
                    .append("},\n");
            json.append("    \"traversable_count\": ").append(stats.traversableCount()).append(",\n");
            json.append("    \"candidate_only_count\": ").append(stats.candidateOnlyCount()).append("\n");
        }
        json.append("  }");
        if (comma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static void appendRoutingCompatibilityAudit(StringBuilder json, RoutingCompatibilityAuditor.RoutingCompatibilityAuditReport audit, boolean comma) {
        json.append("  \"routing_compatibility_audit\": {\n");
        if (audit == null) {
            json.append("    \"audit_version\": \"\",\n");
            json.append("    \"pass\": 0,\n");
            json.append("    \"warn\": 0,\n");
            json.append("    \"info\": 0,\n");
            json.append("    \"items\": []\n");
        } else {
            json.append("    \"audit_version\": \"").append(escape(audit.auditVersion())).append("\",\n");
            json.append("    \"pass\": ").append(audit.passCount()).append(",\n");
            json.append("    \"warn\": ").append(audit.warnCount()).append(",\n");
            json.append("    \"info\": ").append(audit.infoCount()).append(",\n");
            json.append("    \"items\": [\n");
            for (int index = 0; index < audit.items().size(); index++) {
                var item = audit.items().get(index);
                json.append("      {\"id\": \"").append(escape(item.id()))
                        .append("\", \"status\": \"").append(escape(item.status()))
                        .append("\", \"summary\": \"").append(escape(item.summary()))
                        .append("\"}");
                if (index + 1 < audit.items().size()) {
                    json.append(",");
                }
                json.append("\n");
            }
            json.append("    ]\n");
        }
        json.append("  }");
        if (comma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static void appendTransferFootpathAudit(StringBuilder json, TransferFootpathAuditReport audit, boolean comma) {
        json.append("  \"transfer_footpath_audit\": {\n");
        if (audit == null) {
            json.append("    \"audit_version\": \"\",\n");
            json.append("    \"available\": false,\n");
            json.append("    \"pass\": false\n");
        } else {
            appendProperty(json, "audit_version", audit.auditVersion(), true, 4);
            json.append("    \"available\": ").append(audit.available()).append(",\n");
            json.append("    \"pass\": ").append(audit.pass()).append(",\n");
            json.append("    \"raw_transfers\": ").append(audit.rawTransfers()).append(",\n");
            json.append("    \"scoped_transfers\": ").append(audit.scopedTransfers()).append(",\n");
            appendLongMap(json, "transfer_semantic_counts", audit.transferSemanticCounts(), true, 4);
            json.append("    \"transfer_edges\": ").append(audit.transferEdges()).append(",\n");
            json.append("    \"traversable_transfer_edges\": ").append(audit.traversableTransferEdges()).append(",\n");
            json.append("    \"non_pedestrian_gtfs_edges\": ").append(audit.nonPedestrianGtfsEdges()).append(",\n");
            json.append("    \"scoped_gtfs_edges\": ").append(audit.scopedGtfsEdges()).append(",\n");
            json.append("    \"traversable_heuristic_edges\": ").append(audit.traversableHeuristicEdges()).append(",\n");
            json.append("    \"traversable_area_membership_edges\": ").append(audit.traversableAreaMembershipEdges()).append(",\n");
            json.append("    \"stop_footpaths\": ").append(audit.stopFootpaths()).append(",\n");
            json.append("    \"traversable_stop_footpaths\": ").append(audit.traversableStopFootpaths()).append(",\n");
            json.append("    \"unknown_stop_footpaths\": ").append(audit.unknownStopFootpaths()).append(",\n");
            json.append("    \"raw_pathways\": ").append(audit.rawPathways()).append(",\n");
            json.append("    \"pathway_footpaths\": ").append(audit.pathwayFootpaths()).append(",\n");
            json.append("    \"geometry_estimates\": ").append(audit.estimatedFootpaths()).append(",\n");
            json.append("    \"invalid_walk_components\": ").append(audit.invalidWalkComponents()).append(",\n");
            json.append("    \"prohibited_walks\": ").append(audit.prohibitedWalks()).append(",\n");
            json.append("    \"over_distance_traversable_footpaths\": ").append(audit.overDistanceTraversableFootpaths()).append(",\n");
            json.append("    \"zero_time_traversable_footpaths\": ").append(audit.zeroTimeTraversableFootpaths()).append(",\n");
            json.append("    \"multi_stop_areas\": ").append(audit.multiStopAreas()).append(",\n");
            json.append("    \"areas_without_footpath_rows\": ").append(audit.areasWithoutFootpathRows()).append(",\n");
            json.append("    \"oversized_stop_areas\": ").append(audit.oversizedStopAreas()).append(",\n");
            json.append("    \"extreme_stop_areas\": ").append(audit.extremeStopAreas()).append(",\n");
            json.append("    \"maximum_footpath_distance_meters\": ")
                    .append(audit.maximumFootpathDistanceMeters() == null ? "null" : audit.maximumFootpathDistanceMeters())
                    .append(",\n");
            appendStringArray(json, "samples", audit.samples(), false, 4);
        }
        json.append("  }");
        if (comma) json.append(",");
        json.append("\n");
    }

    private static void appendRealFeedValidation(StringBuilder json, RealFeedValidationReport report, boolean comma) {
        json.append("  \"real_feed_validation\": {\n");
        if (report == null) {
            json.append("    \"validation_version\": \"0.6\",\n");
            json.append("    \"input_feed_name\": \"\",\n");
            json.append("    \"generated_at\": \"\",\n");
            json.append("    \"sqlite_file_size_bytes\": 0,\n");
            json.append("    \"total_runtime_ms\": 0,\n");
            json.append("    \"table_row_counts\": {},\n");
            json.append("    \"warning_counts\": {},\n");
            json.append("    \"performance_sections\": {},\n");
            json.append("    \"index_smoke_checks\": {},\n");
            json.append("    \"contract_version\": \"\",\n");
            json.append("    \"preprocessor_version\": \"\",\n");
            json.append("    \"routing_compatibility_audit_status\": \"UNKNOWN\"\n");
        } else {
            appendProperty(json, "validation_version", report.validationVersion(), true, 4);
            appendProperty(json, "input_feed_name", report.inputFeed().getFileName() == null ? report.inputFeed().toString() : report.inputFeed().getFileName().toString(), true, 4);
            appendProperty(json, "generated_at", report.generatedAt().toString(), true, 4);
            json.append("    \"sqlite_file_size_bytes\": ").append(report.sqliteFileSizeBytes()).append(",\n");
            json.append("    \"total_runtime_ms\": ").append(report.performance().totalMs()).append(",\n");
            appendLongMap(json, "table_row_counts", report.tableRowCounts(), true, 4);
            appendIntegerMap(json, "warning_counts", report.warningCounts(), true, 4);
            appendLongMap(json, "performance_sections", report.performance().sectionMs(), true, 4);
            appendLongMap(json, "memory_snapshots_mb", report.performance().memorySnapshotsMb(), true, 4);
            appendStringMap(json, "index_smoke_checks", report.indexSmokeChecks(), true, 4);
            appendProperty(json, "contract_version", report.contractVersion(), true, 4);
            appendProperty(json, "preprocessor_version", report.preprocessorVersion(), true, 4);
            appendProperty(json, "routing_compatibility_audit_status", report.routingCompatibilityAuditStatus(), false, 4);
        }
        json.append("  }");
        if (comma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static void appendSqliteDiagnostics(StringBuilder json, SqliteDiagnosticsReport diagnostics, boolean comma) {
        json.append("  \"sqlite_diagnostics\": {\n");
        if (diagnostics == null) {
            json.append("    \"run_mode\": \"UNKNOWN\",\n");
            json.append("    \"derived_builders_skipped\": false,\n");
            json.append("    \"skipped_derived_builders\": [],\n");
            json.append("    \"batch_size\": 0,\n");
            json.append("    \"stop_times_commit_rows\": 0,\n");
            json.append("    \"stop_times_rows\": 0,\n");
            json.append("    \"stop_times_write_ms\": 0,\n");
            json.append("    \"stop_times_rows_per_second\": 0,\n");
            json.append("    \"stop_times_commit_count\": 0,\n");
            json.append("    \"stop_times_avg_commit_ms\": 0,\n");
            json.append("    \"stop_times_max_commit_ms\": 0,\n");
            json.append("    \"sqlite_size_after_stop_times_bytes\": 0,\n");
            json.append("    \"wal_size_after_stop_times_bytes\": 0,\n");
            json.append("    \"sqlite_pragmas\": {}\n");
        } else {
            appendProperty(json, "run_mode", diagnostics.runMode(), true, 4);
            json.append("    \"derived_builders_skipped\": ").append(diagnostics.derivedBuildersSkipped()).append(",\n");
            json.append("    \"skipped_derived_builders\": [");
            for (int index = 0; index < diagnostics.skippedDerivedBuilders().size(); index++) {
                if (index > 0) {
                    json.append(", ");
                }
                json.append("\"").append(escape(diagnostics.skippedDerivedBuilders().get(index))).append("\"");
            }
            json.append("],\n");
            json.append("    \"batch_size\": ").append(diagnostics.batchSize()).append(",\n");
            json.append("    \"stop_times_commit_rows\": ").append(diagnostics.stopTimesCommitRows()).append(",\n");
            json.append("    \"stop_times_rows\": ").append(diagnostics.stopTimesRows()).append(",\n");
            json.append("    \"stop_times_write_ms\": ").append(diagnostics.stopTimesDurationMs()).append(",\n");
            json.append("    \"stop_times_rows_per_second\": ").append(diagnostics.stopTimesRowsPerSecond()).append(",\n");
            json.append("    \"stop_times_commit_count\": ").append(diagnostics.stopTimesCommitCount()).append(",\n");
            json.append("    \"stop_times_avg_commit_ms\": ").append(diagnostics.stopTimesAverageCommitMs()).append(",\n");
            json.append("    \"stop_times_max_commit_ms\": ").append(diagnostics.stopTimesMaxCommitMs()).append(",\n");
            json.append("    \"sqlite_size_after_stop_times_bytes\": ").append(diagnostics.sqliteSizeAfterStopTimesBytes()).append(",\n");
            json.append("    \"wal_size_after_stop_times_bytes\": ").append(diagnostics.walSizeAfterStopTimesBytes()).append(",\n");
            appendStringMap(json, "sqlite_pragmas", diagnostics.sqlitePragmas(), false, 4);
        }
        json.append("  }");
        if (comma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static void appendAppReadySqlite(StringBuilder json, AppReadySqliteReport report, boolean comma) {
        json.append("  \"app_ready_sqlite\": {\n");
        if (report == null) {
            json.append("    \"app_ready\": false,\n");
            json.append("    \"feature_flags\": {},\n");
            json.append("    \"quality_checks\": {},\n");
            appendDisplayNameAudit(json, null, true, 4);
            appendDisplayNameQualityBaseline(json, null, true, 4);
            json.append("    \"warnings\": []\n");
        } else {
            json.append("    \"app_ready\": ").append(report.appReady()).append(",\n");
            appendBooleanMap(json, "feature_flags", report.featureFlags(), true, 4);
            appendLongMap(json, "quality_checks", report.qualityChecks(), true, 4);
            appendDisplayNameAudit(json, report.displayNameAudit(), true, 4);
            appendDisplayNameQualityBaseline(json, report.displayNameQualityBaseline(), true, 4);
            json.append("    \"warnings\": [");
            for (int index = 0; index < report.warnings().size(); index++) {
                if (index > 0) {
                    json.append(", ");
                }
                json.append("\"").append(escape(report.warnings().get(index))).append("\"");
            }
            json.append("]\n");
        }
        json.append("  }");
        if (comma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static void appendDisplayNameAudit(
            StringBuilder json,
            DisplayNameAuditReport audit,
            boolean comma,
            int indent
    ) {
        json.append(" ".repeat(indent)).append("\"display_name_audit\": {\n");
        if (audit == null) {
            appendProperty(json, "audit_version", "", true, indent + 2);
            json.append(" ".repeat(indent + 2)).append("\"available\": false,\n");
            json.append(" ".repeat(indent + 2)).append("\"pass\": false,\n");
            json.append(" ".repeat(indent + 2)).append("\"scanned_names\": 0,\n");
            json.append(" ".repeat(indent + 2)).append("\"format_mismatches\": 0,\n");
            json.append(" ".repeat(indent + 2)).append("\"municipality_only_names\": 0,\n");
            json.append(" ".repeat(indent + 2)).append("\"duplicate_city_name_prefixes\": 0,\n");
            json.append(" ".repeat(indent + 2)).append("\"matching_city_code_prefixes\": 0,\n");
            json.append(" ".repeat(indent + 2)).append("\"matching_city_qualifiers\": 0,\n");
            json.append(" ".repeat(indent + 2)).append("\"suspicious_unknown_prefixes\": 0,\n");
            json.append(" ".repeat(indent + 2)).append("\"transformed_names\": 0,\n");
            json.append(" ".repeat(indent + 2)).append("\"invalid_transformation_rule_rows\": 0,\n");
            appendLongMap(json, "transformation_rule_counts", Map.of(), true, indent + 2);
            json.append(" ".repeat(indent + 2)).append("\"samples\": []\n");
        } else {
            appendProperty(json, "audit_version", audit.auditVersion(), true, indent + 2);
            json.append(" ".repeat(indent + 2)).append("\"available\": ").append(audit.available()).append(",\n");
            json.append(" ".repeat(indent + 2)).append("\"pass\": ").append(audit.pass()).append(",\n");
            json.append(" ".repeat(indent + 2)).append("\"scanned_names\": ").append(audit.scannedNames()).append(",\n");
            json.append(" ".repeat(indent + 2)).append("\"format_mismatches\": ").append(audit.formatMismatches()).append(",\n");
            json.append(" ".repeat(indent + 2)).append("\"municipality_only_names\": ").append(audit.municipalityOnlyNames()).append(",\n");
            json.append(" ".repeat(indent + 2)).append("\"duplicate_city_name_prefixes\": ").append(audit.duplicateCityNamePrefixes()).append(",\n");
            json.append(" ".repeat(indent + 2)).append("\"matching_city_code_prefixes\": ").append(audit.matchingCityCodePrefixes()).append(",\n");
            json.append(" ".repeat(indent + 2)).append("\"matching_city_qualifiers\": ").append(audit.matchingCityQualifiers()).append(",\n");
            json.append(" ".repeat(indent + 2)).append("\"suspicious_unknown_prefixes\": ").append(audit.suspiciousUnknownPrefixes()).append(",\n");
            json.append(" ".repeat(indent + 2)).append("\"transformed_names\": ").append(audit.transformedNames()).append(",\n");
            json.append(" ".repeat(indent + 2)).append("\"invalid_transformation_rule_rows\": ")
                    .append(audit.invalidTransformationRuleRows()).append(",\n");
            appendLongMap(json, "transformation_rule_counts", audit.transformationRuleCounts(), true, indent + 2);
            json.append(" ".repeat(indent + 2)).append("\"samples\": [");
            for (int index = 0; index < audit.samples().size(); index++) {
                if (index > 0) {
                    json.append(", ");
                }
                json.append("\"").append(escape(audit.samples().get(index))).append("\"");
            }
            json.append("]\n");
        }
        json.append(" ".repeat(indent)).append("}");
        if (comma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static void appendBooleanMap(StringBuilder json, String key, Map<String, Boolean> values, boolean comma, int indent) {
        json.append(" ".repeat(indent)).append("\"").append(escape(key)).append("\": {\n");
        int index = 0;
        for (Map.Entry<String, Boolean> entry : values.entrySet()) {
            json.append(" ".repeat(indent + 2)).append("\"").append(escape(entry.getKey())).append("\": ").append(entry.getValue());
            if (++index < values.size()) {
                json.append(",");
            }
            json.append("\n");
        }
        json.append(" ".repeat(indent)).append("}");
        if (comma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static void appendDisplayNameQualityBaseline(
            StringBuilder json,
            DisplayNameQualityBaselineReport baseline,
            boolean comma,
            int indent
    ) {
        json.append(" ".repeat(indent)).append("\"display_name_quality_baseline\": {\n");
        if (baseline == null) {
            appendProperty(json, "baseline_version", "", true, indent + 2);
            json.append(" ".repeat(indent + 2)).append("\"available\": false,\n");
            json.append(" ".repeat(indent + 2)).append("\"pass\": false,\n");
            json.append(" ".repeat(indent + 2)).append("\"finding_count\": 0,\n");
            json.append(" ".repeat(indent + 2)).append("\"prefix_finding_count\": 0,\n");
            json.append(" ".repeat(indent + 2)).append("\"municipality_only_finding_count\": 0,\n");
            json.append(" ".repeat(indent + 2)).append("\"coverage_gap_count\": 0,\n");
            json.append(" ".repeat(indent + 2)).append("\"destructive_action_count\": 0,\n");
            json.append(" ".repeat(indent + 2)).append("\"classification_counts\": {},\n");
            json.append(" ".repeat(indent + 2)).append("\"samples\": []\n");
        } else {
            appendProperty(json, "baseline_version", baseline.baselineVersion(), true, indent + 2);
            json.append(" ".repeat(indent + 2)).append("\"available\": ").append(baseline.available()).append(",\n");
            json.append(" ".repeat(indent + 2)).append("\"pass\": ").append(baseline.pass()).append(",\n");
            json.append(" ".repeat(indent + 2)).append("\"finding_count\": ").append(baseline.findingCount()).append(",\n");
            json.append(" ".repeat(indent + 2)).append("\"prefix_finding_count\": ").append(baseline.prefixFindingCount()).append(",\n");
            json.append(" ".repeat(indent + 2)).append("\"municipality_only_finding_count\": ").append(baseline.municipalityOnlyFindingCount()).append(",\n");
            json.append(" ".repeat(indent + 2)).append("\"coverage_gap_count\": ").append(baseline.coverageGapCount()).append(",\n");
            json.append(" ".repeat(indent + 2)).append("\"destructive_action_count\": ").append(baseline.destructiveActionCount()).append(",\n");
            appendLongMap(json, "classification_counts", baseline.classificationCounts(), true, indent + 2);
            json.append(" ".repeat(indent + 2)).append("\"samples\": [");
            for (int index = 0; index < baseline.samples().size(); index++) {
                if (index > 0) {
                    json.append(", ");
                }
                json.append("\"").append(escape(baseline.samples().get(index))).append("\"");
            }
            json.append("]\n");
        }
        json.append(" ".repeat(indent)).append("}");
        if (comma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static void appendIntegerMap(StringBuilder json, String key, Map<String, Integer> values, boolean comma, int indent) {
        json.append(" ".repeat(indent)).append("\"").append(escape(key)).append("\": {");
        int index = 0;
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            if (index > 0) {
                json.append(", ");
            }
            json.append("\"").append(escape(entry.getKey())).append("\": ").append(entry.getValue());
            index++;
        }
        json.append("}");
        if (comma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static void appendStringMap(StringBuilder json, String key, Map<String, String> values, boolean comma, int indent) {
        json.append(" ".repeat(indent)).append("\"").append(escape(key)).append("\": {");
        int index = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (index > 0) {
                json.append(", ");
            }
            json.append("\"").append(escape(entry.getKey())).append("\": \"").append(escape(entry.getValue())).append("\"");
            index++;
        }
        json.append("}");
        if (comma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static void appendRoutesWithMostAxes(StringBuilder json, RouteAxisBuilder.RouteAxisStats stats) {
        json.append("    \"routes_with_most_axes\": {");
        List<Map.Entry<String, Integer>> routes = stats.routesWithMostAxes(10);
        for (int index = 0; index < routes.size(); index++) {
            if (index > 0) {
                json.append(", ");
            }
            Map.Entry<String, Integer> entry = routes.get(index);
            json.append("\"").append(escape(entry.getKey())).append("\": ").append(entry.getValue());
        }
        json.append("}\n");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String formatDouble(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
