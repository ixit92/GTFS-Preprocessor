package de.ixit.gtfs.model;

public record Agency(String agencyId, String agencyName, String agencyTimezone) {
    public Agency(String agencyId, String agencyName) {
        this(agencyId, agencyName, "");
    }
}
