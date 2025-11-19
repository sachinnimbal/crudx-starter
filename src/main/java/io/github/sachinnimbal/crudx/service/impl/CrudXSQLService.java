package io.github.sachinnimbal.crudx.service.impl;

import io.github.sachinnimbal.crudx.core.model.CrudXBaseEntity;
import io.github.sachinnimbal.crudx.core.response.BatchResult;
import io.github.sachinnimbal.crudx.service.CrudXService;
import io.github.sachinnimbal.crudx.service.impl.sql.CrudXSQLBatchOperations;
import io.github.sachinnimbal.crudx.service.impl.sql.CrudXSQLDeleteOperations;
import io.github.sachinnimbal.crudx.service.impl.sql.CrudXSQLReadOperations;
import io.github.sachinnimbal.crudx.service.impl.sql.CrudXSQLUpdateOperations;
import io.github.sachinnimbal.crudx.service.impl.sql.helper.CrudXSQLValidationHelper;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.GenericTypeResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

@Slf4j
@Transactional
public abstract class CrudXSQLService<T extends CrudXBaseEntity<ID>, ID extends Serializable>
        implements CrudXService<T, ID> {

    @Autowired(required = false)
    protected EntityManager entityManager;

    protected Class<T> entityClass;

    // Specialized operation components
    private CrudXSQLBatchOperations<T, ID> batchOperations;
    private CrudXSQLReadOperations<T, ID> readOperations;
    private CrudXSQLUpdateOperations<T, ID> updateOperations;
    private CrudXSQLDeleteOperations<T, ID> deleteOperations;
    private CrudXSQLValidationHelper<T, ID> validationHelper;

    @PostConstruct
    @SuppressWarnings("unchecked")
    protected void init() {
        if (entityManager == null) {
            throw new IllegalStateException(
                    "EntityManager not available. Add 'spring-boot-starter-data-jpa' and DB driver.");
        }

        // Resolve entity class
        Type genericSuperclass = getClass().getGenericSuperclass();
        if (genericSuperclass instanceof ParameterizedType paramType) {
            Type[] typeArgs = paramType.getActualTypeArguments();
            if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                entityClass = (Class<T>) typeArgs[0];
                log.debug("Entity class: {}", entityClass.getSimpleName());
            }
        }

        if (entityClass == null) {
            Class<?>[] typeArgs = GenericTypeResolver.resolveTypeArguments(getClass(), CrudXSQLService.class);
            if (typeArgs != null && typeArgs.length > 0) {
                entityClass = (Class<T>) typeArgs[0];
            }
        }

        if (entityClass == null) {
            throw new IllegalStateException("Could not resolve entity class: " + getClass().getSimpleName());
        }

        // Initialize operation components
        initializeComponents();

        log.info("✅ SQL Service initialized: {} with modular architecture", entityClass.getSimpleName());
    }

    /**
     * Initialize specialized operation components
     */
    private void initializeComponents() {
        // Validation helper (shared across components)
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        this.validationHelper = new CrudXSQLValidationHelper<>(validator);

        this.readOperations = new CrudXSQLReadOperations<>(entityManager);

        this.updateOperations = new CrudXSQLUpdateOperations<>(
                entityManager,
                validator,
                validationHelper,
                readOperations
        );

        // Delete operations
        this.deleteOperations = new CrudXSQLDeleteOperations<>(
                entityManager,
                readOperations
        );

        // Batch operations (most complex)
        this.batchOperations = new CrudXSQLBatchOperations<>(
                entityManager,
                validationHelper
        );
    }

    // ==================== CREATE OPERATIONS ====================

    @Override
    @Transactional(timeout = 300)
    public T create(T entity) {
        long start = System.currentTimeMillis();
        log.debug("Creating entity: {}", entityClass.getSimpleName());

        // Validate unique constraints
        if (validationHelper.violatesUniqueConstraints(entity, entityClass, entityManager)) {
            throw new io.github.sachinnimbal.crudx.core.exception.DuplicateEntityException(
                    validationHelper.buildDuplicateMessage(entity, entityClass));
        }

        entityManager.persist(entity);
        entityManager.flush();

        log.info("Entity created: {} in {} ms", entity.getId(), System.currentTimeMillis() - start);
        return entity;
    }

    @Override
    public BatchResult<T> createBatch(List<T> entities, boolean skipDuplicates) {
        return batchOperations.createBatch(entities, skipDuplicates, entityClass);
    }

    // ==================== READ OPERATIONS ====================

    @Override
    public T findById(ID id) {
        return readOperations.findById(id, entityClass);
    }

    @Override
    public List<T> findAll() {
        return readOperations.findAll(entityClass);
    }

    @Override
    public List<T> findAll(Sort sort) {
        return readOperations.findAll(sort, entityClass);
    }

    @Override
    public Page<T> findAll(Pageable pageable) {
        return readOperations.findAll(pageable, entityClass);
    }

    @Override
    public long count() {
        return readOperations.count(entityClass);
    }

    @Override
    public boolean existsById(ID id) {
        return readOperations.existsById(id, entityClass);
    }

    // ==================== UPDATE OPERATIONS ====================

    @Override
    public T update(ID id, Map<String, Object> updates) {
        return updateOperations.update(id, updates, entityClass);
    }

    @Override
    public BatchResult<T> updateBatch(Map<ID, Map<String, Object>> updates) {
        return updateOperations.updateBatch(updates, entityClass);
    }

    // ==================== DELETE OPERATIONS ====================

    @Override
    public T delete(ID id) {
        return deleteOperations.delete(id, entityClass);
    }

    @Override
    public BatchResult<T> deleteBatch(List<ID> ids) {
        return deleteOperations.deleteBatch(ids, entityClass);
    }
}