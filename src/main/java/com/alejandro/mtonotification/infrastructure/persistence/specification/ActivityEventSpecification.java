package com.alejandro.mtonotification.infrastructure.persistence.specification;

import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
import com.alejandro.mtonotification.infrastructure.persistence.entity.ActivityEvent;
import org.springframework.data.jpa.domain.Specification;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Collection;

/** Los filtros del registro y de los accesos. */
public final class ActivityEventSpecification {

    private ActivityEventSpecification() {
    }

    public static Specification<ActivityEvent> categoryEquals(ActivityCategory category) {
        return SpecificationUtils.equalsValue("category", category);
    }

    public static Specification<ActivityEvent> categoryNot(ActivityCategory category) {
        return (root, query, builder) -> builder.notEqual(root.get("category"), category);
    }

    public static Specification<ActivityEvent> typeEquals(String type) {
        return SpecificationUtils.equalsIgnoreCase("type", type);
    }

    public static Specification<ActivityEvent> typeIn(Collection<String> types) {
        if (types == null || types.isEmpty()) {
            return SpecificationUtils.alwaysTrue();
        }
        return (root, query, builder) -> root.get("type").in(types);
    }

    public static Specification<ActivityEvent> typeNotIn(Collection<String> types) {
        if (types == null || types.isEmpty()) {
            return SpecificationUtils.alwaysTrue();
        }
        return (root, query, builder) -> builder.not(root.get("type").in(types));
    }

    public static Specification<ActivityEvent> severityEquals(ActivitySeverity severity) {
        return SpecificationUtils.equalsValue("severity", severity);
    }

    public static Specification<ActivityEvent> actorUsernameEquals(String username) {
        return SpecificationUtils.equalsIgnoreCase("actorUsername", username);
    }

    public static Specification<ActivityEvent> subjectTypeEquals(String subjectType) {
        return SpecificationUtils.equalsIgnoreCase("subjectType", subjectType);
    }

    public static Specification<ActivityEvent> subjectIdEquals(String subjectId) {
        return SpecificationUtils.equalsValue("subjectId", SpecificationUtils.normalize(subjectId));
    }

    public static Specification<ActivityEvent> sourceServiceEquals(String sourceService) {
        return SpecificationUtils.equalsIgnoreCase("sourceService", sourceService);
    }

    public static Specification<ActivityEvent> ipAddressEquals(InetAddress ipAddress) {
        return SpecificationUtils.equalsValue("ipAddress", ipAddress);
    }

    public static Specification<ActivityEvent> occurredBetween(Instant from, Instant to) {
        return SpecificationUtils.between("occurredAt", from, to);
    }

    /** Lo que otro evento dejo obsoleto no se ensena salvo que se pida. */
    public static Specification<ActivityEvent> notSuperseded() {
        return (root, query, builder) -> builder.isNull(root.get("supersededBy"));
    }
}
