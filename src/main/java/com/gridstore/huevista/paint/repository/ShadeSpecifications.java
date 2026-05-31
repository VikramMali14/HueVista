package com.gridstore.huevista.paint.repository;

import com.gridstore.huevista.paint.model.Shade;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public final class ShadeSpecifications {

    private ShadeSpecifications() {}

    /**
     * Build a Specification that applies only the filters the caller actually
     * provided. Each null/blank parameter is simply omitted, so the SQL never
     * contains a typeless null inside LOWER(?) — which on PostgreSQL would
     * blow up with "function lower(bytea) does not exist".
     */
    public static Specification<Shade> withFilters(
            String brand,
            String family,
            String temperature,
            String tonality,
            String search
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (isPresent(brand)) {
                predicates.add(cb.equal(root.get("brand").get("slug"), brand));
            }
            if (isPresent(family)) {
                predicates.add(cb.equal(cb.lower(root.get("shadeFamily")), family.toLowerCase()));
            }
            if (isPresent(temperature)) {
                predicates.add(cb.equal(cb.lower(root.get("colorTemperature")), temperature.toLowerCase()));
            }
            if (isPresent(tonality)) {
                predicates.add(cb.equal(cb.lower(root.get("tonality")), tonality.toLowerCase()));
            }
            if (isPresent(search)) {
                String like = "%" + search.toLowerCase() + "%";
                Predicate nameMatch = cb.like(cb.lower(root.get("name")), like);
                Predicate codeMatch = cb.equal(root.get("shadeCode"), search);
                predicates.add(cb.or(nameMatch, codeMatch));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static boolean isPresent(String v) {
        return v != null && !v.isBlank();
    }
}
