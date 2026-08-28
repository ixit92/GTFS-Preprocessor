package de.ixit.gtfs;

import java.util.List;
import java.util.Map;

public record RoutingContractConsumerReport(
        String consumerVersion,
        String generatedAt,
        String database,
        ContractEvidence contract,
        AreaEvidence startArea,
        AreaEvidence targetArea,
        String serviceDate,
        int fromSeconds,
        int toSeconds,
        int activeServiceCount,
        int directCandidateCount,
        boolean overflowTimeObserved,
        List<ValidatedLeg> validatedLegs,
        Map<String, String> checks,
        List<String> failures,
        String decisionScope,
        boolean pass
) {
    public record ContractEvidence(
            String contractName,
            String contractVersion,
            List<String> acceptedContractVersions,
            String preprocessorVersion,
            String timeModel,
            String stopIdPolicy,
            String areaIdPolicy,
            String searchTokensPolicy,
            String serviceDayResolutionPolicy,
            String serviceDayTimezonePolicy,
            String serviceDayTimeOverflowPolicy
    ) {
    }

    public record AreaEvidence(
            String areaId,
            String displayName,
            List<MemberEvidence> concreteMembers
    ) {
    }

    public record MemberEvidence(String stopId, String stopName) {
    }

    public record ValidatedLeg(
            String tripId,
            String routeId,
            String serviceId,
            String startStopId,
            String targetStopId,
            int startSequence,
            int targetSequence,
            int departureSeconds,
            int arrivalSeconds,
            String departureTime,
            String arrivalTime,
            String serviceActiveReason,
            boolean tripMetadataValid,
            boolean serviceDayValid,
            boolean stopMembershipValid,
            boolean stopTimePathValid,
            boolean overflowTime,
            boolean valid
    ) {
    }
}
