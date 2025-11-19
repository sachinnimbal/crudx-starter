package io.github.sachinnimbal.crudx.service.impl.mongo.helper;

import io.github.sachinnimbal.crudx.core.annotations.CrudXUniqueConstraint;
import io.github.sachinnimbal.crudx.core.annotations.CrudXUniqueConstraints;
import io.github.sachinnimbal.crudx.core.model.CrudXMongoEntity;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Enterprise MongoDB validation helper with bulk query optimization
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrudXMongoValidationHelper<T extends CrudXMongoEntity<ID>, ID extends Serializable> {

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
     * Build constraint key for in-memory deduplication
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
     * ENTERPRISE OPTIMIZATION: Single bulk query for all entities
     * Uses MongoDB $or operator to check duplicates in ONE database hit
     */
    public Set<String> bulkCheckDuplicates(List<T> entities, Class<T> entityClass, MongoTemplate mongoTemplate) {
        CrudXUniqueConstraint[] constraints = getUniqueConstraints(entityClass);
        if (constraints == null || constraints.length == 0) {
            return Collections.emptySet();
        }

        try {
            List<Criteria> orCriteria = new ArrayList<>();

            // Build OR conditions for all entities
            for (T entity : entities) {
                for (CrudXUniqueConstraint constraint : constraints) {
                    List<Criteria> andCriteria = new ArrayList<>();
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
                            andCriteria.add(Criteria.where(fieldName).is(value));
                        } catch (Exception e) {
                            allFieldsHaveValues = false;
                            break;
                        }
                    }

                    if (allFieldsHaveValues && !andCriteria.isEmpty()) {
                        Criteria andCombined = new Criteria().andOperator(
                                andCriteria.toArray(new Criteria[0])
                        );
                        orCriteria.add(andCombined);
                    }
                }
            }

            if (orCriteria.isEmpty()) {
                return Collections.emptySet();
            }

            // Single database query with all OR conditions
            Query query = new Query(new Criteria().orOperator(
                    orCriteria.toArray(new Criteria[0])
            ));

            List<T> existingEntities = mongoTemplate.find(query, entityClass);

            // Build set of existing constraint keys
            Set<String> existingKeys = new HashSet<>();
            for (T existing : existingEntities) {
                String key = buildConstraintKey(existing, entityClass);
                if (key != null) {
                    existingKeys.add(key);
                }
            }

            log.debug("✅ MongoDB bulk check: {} entities checked, {} duplicates found in 1 DB hit",
                    entities.size(), existingKeys.size());

            return existingKeys;

        } catch (Exception e) {
            log.warn("Bulk duplicate check failed: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * Single entity duplicate check
     */
    public boolean violatesUniqueConstraints(T entity, Class<T> entityClass, MongoTemplate mongoTemplate) {
        CrudXUniqueConstraint[] constraints = getUniqueConstraints(entityClass);
        if (constraints == null || constraints.length == 0) {
            return false;
        }

        for (CrudXUniqueConstraint constraint : constraints) {
            if (checkDuplicateInDB(entity, constraint, entityClass, mongoTemplate)) {
                return true;
            }
        }

        return false;
    }

    private boolean checkDuplicateInDB(T entity, CrudXUniqueConstraint constraint,
                                       Class<T> entityClass, MongoTemplate mongoTemplate) {
        try {
            Query query = new Query();
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
                    query.addCriteria(Criteria.where(fieldName).is(value));
                } catch (Exception e) {
                    return false;
                }
            }

            if (!hasAllValues) {
                return false;
            }

            if (entity.getId() != null) {
                query.addCriteria(Criteria.where("_id").ne(entity.getId()));
            }

            return mongoTemplate.exists(query, entityClass);

        } catch (Exception e) {
            log.warn("Duplicate check failed: {}", e.getMessage());
            return false;
        }
    }

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

    private CrudXUniqueConstraint[] getUniqueConstraints(Class<T> entityClass) {
        CrudXUniqueConstraints containerAnnotation = entityClass.getAnnotation(CrudXUniqueConstraints.class);

        if (containerAnnotation != null) {
            return containerAnnotation.value();
        }

        return entityClass.getAnnotationsByType(CrudXUniqueConstraint.class);
    }

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