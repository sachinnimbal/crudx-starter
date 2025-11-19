package io.github.sachinnimbal.crudx.service.impl.sql.helper;

import io.github.sachinnimbal.crudx.core.annotations.CrudXUniqueConstraint;
import io.github.sachinnimbal.crudx.core.annotations.CrudXUniqueConstraints;
import io.github.sachinnimbal.crudx.core.model.CrudXBaseEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Enterprise SQL validation helper with optimized database queries
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrudXSQLValidationHelper<T extends CrudXBaseEntity<ID>, ID extends Serializable> {

    private final Validator validator;

    /**
     * Jakarta Bean Validation
     */
    public void validateJakartaConstraints(T entity) {
        if (validator == null) return;

        Set<ConstraintViolation<T>> violations = validator.validate(entity);
        if (!violations.isEmpty()) {
            String errors = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException("Validation failed: " + errors);
        }
    }

    /**
     * Build unique constraint key for in-memory deduplication
     * Returns null if any required field is missing
     */
    public String buildConstraintKey(T entity, Class<T> entityClass) {
        CrudXUniqueConstraint[] constraints = getUniqueConstraints(entityClass);
        if (constraints == null || constraints.length == 0) {
            return null;
        }

        StringBuilder key = new StringBuilder();
        boolean hasAnyValue = false;

        for (CrudXUniqueConstraint constraint : constraints) {
            key.append(constraint.name()).append(":");
            boolean allFieldsHaveValues = true;

            for (String fieldName : constraint.fields()) {
                try {
                    Field field = getFieldFromClass(entityClass, fieldName);
                    field.setAccessible(true);
                    Object value = field.get(entity);

                    if (value == null) {
                        allFieldsHaveValues = false;
                        break;
                    }
                    key.append(fieldName).append("=").append(value).append("|");
                    hasAnyValue = true;
                } catch (Exception e) {
                    allFieldsHaveValues = false;
                    break;
                }
            }

            if (!allFieldsHaveValues) {
                int lastColon = key.lastIndexOf(constraint.name() + ":");
                if (lastColon >= 0) {
                    key.setLength(lastColon);
                }
            } else {
                key.append(";");
            }
        }

        return hasAnyValue ? key.toString() : null;
    }

    /**
     * ENTERPRISE OPTIMIZATION: Single bulk query to check duplicates for entire batch
     * Instead of N queries, we do 1 query with OR conditions
     */
    public Set<String> bulkCheckDuplicates(List<T> entities, Class<T> entityClass, EntityManager entityManager) {
        CrudXUniqueConstraint[] constraints = getUniqueConstraints(entityClass);
        if (constraints == null || constraints.length == 0) {
            return Collections.emptySet();
        }

        try {
            CriteriaBuilder cb = entityManager.getCriteriaBuilder();
            CriteriaQuery<T> query = cb.createQuery(entityClass);
            Root<T> root = query.from(entityClass);

            // Build OR conditions for all entities
            List<Predicate> orPredicates = new ArrayList<>();

            for (T entity : entities) {
                for (CrudXUniqueConstraint constraint : constraints) {
                    List<Predicate> andPredicates = new ArrayList<>();
                    boolean allFieldsHaveValues = true;

                    for (String fieldName : constraint.fields()) {
                        try {
                            Field field = getFieldFromClass(entityClass, fieldName);
                            field.setAccessible(true);
                            Object value = field.get(entity);

                            if (value == null) {
                                allFieldsHaveValues = false;
                                break;
                            }
                            andPredicates.add(cb.equal(root.get(fieldName), value));
                        } catch (Exception e) {
                            allFieldsHaveValues = false;
                            break;
                        }
                    }

                    // Only add if all fields have values
                    if (allFieldsHaveValues && !andPredicates.isEmpty()) {
                        orPredicates.add(cb.and(andPredicates.toArray(new Predicate[0])));
                    }
                }
            }

            if (orPredicates.isEmpty()) {
                return Collections.emptySet();
            }

            // Execute SINGLE query with all OR conditions
            query.select(root).where(cb.or(orPredicates.toArray(new Predicate[0])));
            TypedQuery<T> typedQuery = entityManager.createQuery(query);
            List<T> existingEntities = typedQuery.getResultList();

            // Build set of existing constraint keys
            Set<String> existingKeys = new HashSet<>();
            for (T existing : existingEntities) {
                String key = buildConstraintKey(existing, entityClass);
                if (key != null) {
                    existingKeys.add(key);
                }
            }

            log.debug("✅ Bulk duplicate check: {} entities checked, {} duplicates found in 1 DB hit",
                    entities.size(), existingKeys.size());

            return existingKeys;

        } catch (Exception e) {
            log.warn("Bulk duplicate check failed: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * Single entity duplicate check (used for single create/update)
     */
    public boolean violatesUniqueConstraints(T entity, Class<T> entityClass, EntityManager entityManager) {
        CrudXUniqueConstraint[] constraints = getUniqueConstraints(entityClass);
        if (constraints == null || constraints.length == 0) {
            return false;
        }

        for (CrudXUniqueConstraint constraint : constraints) {
            if (checkDuplicateInDB(entity, constraint, entityClass, entityManager)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Database duplicate check for single entity
     */
    private boolean checkDuplicateInDB(T entity, CrudXUniqueConstraint constraint,
                                       Class<T> entityClass, EntityManager entityManager) {
        try {
            CriteriaBuilder cb = entityManager.getCriteriaBuilder();
            CriteriaQuery<Long> query = cb.createQuery(Long.class);
            Root<T> root = query.from(entityClass);

            List<Predicate> predicates = new ArrayList<>();
            boolean hasAllValues = true;

            for (String fieldName : constraint.fields()) {
                try {
                    Field field = getFieldFromClass(entityClass, fieldName);
                    field.setAccessible(true);
                    Object value = field.get(entity);

                    if (value == null) {
                        hasAllValues = false;
                        break;
                    }
                    predicates.add(cb.equal(root.get(fieldName), value));
                } catch (Exception e) {
                    return false;
                }
            }

            if (!hasAllValues) {
                return false;
            }

            // Exclude current entity if updating
            if (entity.getId() != null) {
                predicates.add(cb.notEqual(root.get("id"), entity.getId()));
            }

            if (predicates.isEmpty()) {
                return false;
            }

            query.select(cb.count(root)).where(predicates.toArray(new Predicate[0]));
            Long count = entityManager.createQuery(query).getSingleResult();

            return count > 0;

        } catch (Exception e) {
            log.warn("Duplicate check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Build human-readable duplicate message
     */
    public String buildDuplicateMessage(T entity, Class<T> entityClass) {
        CrudXUniqueConstraint[] constraints = getUniqueConstraints(entityClass);
        if (constraints == null || constraints.length == 0) {
            return "Duplicate entry detected";
        }

        for (CrudXUniqueConstraint constraint : constraints) {
            if (!constraint.message().isEmpty()) {
                return constraint.message();
            }

            StringBuilder msg = new StringBuilder("Duplicate constraint '");
            msg.append(constraint.name()).append("': Fields [");

            for (String fieldName : constraint.fields()) {
                try {
                    Field field = getFieldFromClass(entityClass, fieldName);
                    field.setAccessible(true);
                    Object value = field.get(entity);
                    msg.append(fieldName).append("=").append(value).append(", ");
                } catch (Exception e) {
                    msg.append(fieldName).append(", ");
                }
            }
            msg.setLength(msg.length() - 2);
            msg.append("] already exist");
            return msg.toString();
        }

        return "Duplicate entry detected";
    }

    /**
     * Get unique constraints from entity class
     */
    private CrudXUniqueConstraint[] getUniqueConstraints(Class<T> entityClass) {
        CrudXUniqueConstraints containerAnnotation = entityClass.getAnnotation(CrudXUniqueConstraints.class);

        if (containerAnnotation != null) {
            return containerAnnotation.value();
        }

        return entityClass.getAnnotationsByType(CrudXUniqueConstraint.class);
    }

    /**
     * Get field from class hierarchy
     */
    private Field getFieldFromClass(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) {
                return getFieldFromClass(clazz.getSuperclass(), fieldName);
            }
            throw e;
        }
    }
}