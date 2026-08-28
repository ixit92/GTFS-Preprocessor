package de.ixit.gtfs;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface TransitDataAccess extends AutoCloseable {
    ResolvedStopAreaData resolveStopArea(String areaId) throws SQLException;

    List<StopAreaMemberData> getStopAreaMembers(String areaId) throws SQLException;

    List<TransitDepartureData> findDepartures(
            String areaId,
            int fromSeconds,
            int toSeconds,
            Set<String> activeServiceIds,
            int limit
    ) throws SQLException;

    List<DirectTransitLegData> findDirectLegs(
            String startAreaId,
            String targetAreaId,
            LocalDate date,
            int fromSeconds,
            int toSeconds,
            int limit
    ) throws SQLException;

    List<NextTransitLegData> findNextLegs(
            String fromAreaId,
            LocalDate date,
            int earliestDepartureSeconds,
            int latestDepartureSeconds,
            int minArrivalAfterDepartureSeconds,
            Set<String> activeServiceIds,
            int limit
    ) throws SQLException;

    List<TripStopTimeData> getTripStopTimes(String tripId) throws SQLException;

    TripMetadataData getTripMetadata(String tripId) throws SQLException;

    Set<String> findActiveServiceIds(LocalDate date) throws SQLException;

    ServiceActiveData getServiceActiveData(String serviceId, LocalDate date) throws SQLException;

    @Override
    void close() throws SQLException;

    record ResolvedStopAreaData(
            String areaId,
            String displayName,
            double lat,
            double lon,
            List<StopAreaMemberData> members
    ) {
    }

    record StopAreaMemberData(
            String areaId,
            String stopId,
            String stopName
    ) {
    }

    record TransitDepartureData(
            String areaId,
            String stopId,
            String stopName,
            String tripId,
            String routeId,
            String routeShortName,
            String routeLongName,
            String serviceId,
            int departureSeconds,
            int stopSequence
    ) {
    }

    record TripMetadataData(
            String tripId,
            String routeId,
            String routeShortName,
            String routeLongName,
            String serviceId
    ) {
    }

    record TripStopTimeData(
            String tripId,
            String stopId,
            String stopName,
            String areaId,
            int arrivalSeconds,
            int departureSeconds,
            int stopSequence
    ) {
    }

    record ServiceActiveData(
            String serviceId,
            boolean active,
            String reason
    ) {
    }

    record DirectTransitLegData(
            String tripId,
            String routeId,
            String routeShortName,
            String routeLongName,
            String serviceId,
            String startStopId,
            String startStopName,
            String targetStopId,
            String targetStopName,
            int startDepartureSeconds,
            int targetArrivalSeconds,
            int durationMinutes,
            int startSequence,
            int targetSequence,
            String serviceActiveReason
    ) {
    }

    record NextTransitLegData(
            String fromAreaId,
            String toAreaId,
            String toAreaName,
            String tripId,
            String routeId,
            String routeShortName,
            String routeLongName,
            String serviceId,
            String fromStopId,
            String fromStopName,
            String toStopId,
            String toStopName,
            int departureSeconds,
            int arrivalSeconds,
            int durationMinutes,
            int fromSequence,
            int toSequence,
            String serviceActiveReason
    ) {
    }
}
