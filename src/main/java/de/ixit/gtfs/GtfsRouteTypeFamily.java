package de.ixit.gtfs;

/** Maps extended GTFS route types to the closest standard comparison family. */
public final class GtfsRouteTypeFamily {
    private GtfsRouteTypeFamily() {
    }

    public static int canonical(Integer routeType) {
        if (routeType == null) {
            return -1;
        }
        if (between(routeType, 100, 199) || between(routeType, 300, 399)) {
            return 2;
        }
        if (between(routeType, 200, 299) || between(routeType, 700, 799)) {
            return 3;
        }
        if (between(routeType, 400, 699)) {
            return 1;
        }
        if (between(routeType, 800, 899)) {
            return 11;
        }
        if (between(routeType, 900, 999)) {
            return 0;
        }
        if (between(routeType, 1000, 1099) || between(routeType, 1200, 1299)) {
            return 4;
        }
        if (between(routeType, 1300, 1399)) {
            return 6;
        }
        if (between(routeType, 1400, 1499)) {
            return 7;
        }
        return routeType;
    }

    private static boolean between(int value, int minimum, int maximum) {
        return value >= minimum && value <= maximum;
    }
}
