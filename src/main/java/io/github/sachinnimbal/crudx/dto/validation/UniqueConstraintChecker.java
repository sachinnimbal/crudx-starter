package io.github.sachinnimbal.crudx.dto.validation;

import io.github.sachinnimbal.crudx.core.config.CrudXDtoProperties;
import io.github.sachinnimbal.crudx.core.enums.Direction;
import io.github.sachinnimbal.crudx.core.enums.OperationType;
import io.github.sachinnimbal.crudx.dto.metadata.DtoMetadata;
import io.github.sachinnimbal.crudx.dto.metadata.UniqueConstraintMetadata;
import io.github.sachinnimbal.crudx.dto.registry.DtoRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Pre-check unique constraints before mapping (OPTIONAL optimization)
 * Saves time by failing fast instead of processing entire DTO
 */
@Slf4j
@Component
@ConditionalOnClass(name = "jakarta.persistence.EntityManager")
public class UniqueConstraintChecker {

    @Autowired(required = false)
    @Lazy
    private EntityManager entityManager;

    @Autowired(required = false)
    private MongoTemplate mongoTemplate;

    private final DtoRegistry registry;
    private final CrudXDtoProperties properties;

    public UniqueConstraintChecker(DtoRegistry registry, CrudXDtoProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * Check unique constraints for DTO before creating entity
     */
    public <D> UniqueCheckResult checkUniqueConstraints(D dto,
                                                        Class<?> entityClass,
                                                        OperationType operation) {
        if (!properties.isUniquePreCheck()) {
            return UniqueCheckResult.success(); // Feature disabled
        }

        DtoMetadata metadata = registry.findDto(entityClass, operation, Direction.REQUEST);

        if (metadata == null || metadata.getUniqueConstraints().isEmpty()) {
            return UniqueCheckResult.success(); // No constraints
        }

        long startTime = System.currentTimeMillis();
        UniqueCheckResult result = new UniqueCheckResult();

        try {
            for (UniqueConstraintMetadata constraint : metadata.getUniqueConstraints()) {
                boolean isDuplicate = isSQLEntity(entityClass)
                        ? checkSQLUnique(dto, entityClass, constraint)
                        : checkMongoUnique(dto, entityClass, constraint);

                if (isDuplicate) {
                    result.setUnique(false);
                    result.setViolatedConstraint(constraint);
                    result.setMessage(constraint.getMessage().isEmpty()
                            ? String.format("Duplicate entry for fields: %s",
                            String.join(", ", constraint.getFields()))
                            : constraint.getMessage());
                    break;
                }
            }

            long duration = System.currentTimeMillis() - startTime;

            // Warn if pre-check takes too long
            if (duration > properties.getUniquePreCheckTimeoutMs()) {
                log.warn("Unique constraint pre-check took {}ms (threshold: {}ms). " +
                                "Consider disabling pre-check or optimizing database indexes.",
                        duration, properties.getUniquePreCheckTimeoutMs());
            }

        } catch (Exception e) {
            log.error("Error during unique constraint pre-check", e);
            // Don't fail the request - let service layer handle it
            return UniqueCheckResult.success();
        }

        return result;
    }

    private <D> boolean checkSQLUnique(D dto, Class<?> entityClass,
                                       UniqueConstraintMetadata constraint) {
        if (entityManager == null) return false;

        try {
            CriteriaBuilder cb = entityManager.getCriteriaBuilder();
            CriteriaQuery<Long> query = cb.createQuery(Long.class);
            Root<?> root = query.from(entityClass);

            List<Predicate> predicates = new ArrayList<>();

            for (String fieldName : constraint.getFields()) {
                Field dtoField = dto.getClass().getDeclaredField(fieldName);
                dtoField.setAccessible(true);
                Object value = dtoField.get(dto);

                if (value != null) {
                    predicates.add(cb.equal(root.get(fieldName), value));
                }
            }

            query.select(cb.count(root));
            query.where(predicates.toArray(new Predicate[0]));

            Long count = entityManager.createQuery(query).getSingleResult();
            return count > 0;

        } catch (Exception e) {
            log.error("Error checking SQL unique constraint", e);
            return false;
        }
    }

    private <D> boolean checkMongoUnique(D dto, Class<?> entityClass,
                                         UniqueConstraintMetadata constraint) {
        if (mongoTemplate == null) return false;

        try {
            Query query = new Query();

            for (String fieldName : constraint.getFields()) {
                Field dtoField = dto.getClass().getDeclaredField(fieldName);
                dtoField.setAccessible(true);
                Object value = dtoField.get(dto);

                if (value != null) {
                    query.addCriteria(Criteria.where(fieldName).is(value));
                }
            }

            return mongoTemplate.exists(query, entityClass);

        } catch (Exception e) {
            log.error("Error checking MongoDB unique constraint", e);
            return false;
        }
    }

    private boolean isSQLEntity(Class<?> entityClass) {
        try {
            return Class.forName("io.github.sachinnimbal.crudx.core.model.CrudXMySQLEntity")
                    .isAssignableFrom(entityClass) ||
                    Class.forName("io.github.sachinnimbal.crudx.core.model.CrudXPostgreSQLEntity")
                            .isAssignableFrom(entityClass);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}