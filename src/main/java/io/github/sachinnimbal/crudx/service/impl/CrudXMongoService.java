package io.github.sachinnimbal.crudx.service.impl;

import io.github.sachinnimbal.crudx.core.model.CrudXMongoEntity;
import io.github.sachinnimbal.crudx.core.response.BatchResult;
import io.github.sachinnimbal.crudx.service.CrudXService;
import io.github.sachinnimbal.crudx.service.impl.mongo.CrudXMongoBatchOperations;
import io.github.sachinnimbal.crudx.service.impl.mongo.CrudXMongoDeleteOperations;
import io.github.sachinnimbal.crudx.service.impl.mongo.CrudXMongoReadOperations;
import io.github.sachinnimbal.crudx.service.impl.mongo.CrudXMongoUpdateOperations;
import io.github.sachinnimbal.crudx.service.impl.mongo.helper.CrudXMongoValidationHelper;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.GenericTypeResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

@Slf4j
public abstract class CrudXMongoService<T extends CrudXMongoEntity<ID>, ID extends Serializable>
        implements CrudXService<T, ID> {

    @Autowired(required = false)
    protected MongoTemplate mongoTemplate;

    protected Class<T> entityClass;

    // Specialized components
    private CrudXMongoBatchOperations<T, ID> batchOperations;
    private CrudXMongoReadOperations<T, ID> readOperations;
    private CrudXMongoUpdateOperations<T, ID> updateOperations;
    private CrudXMongoDeleteOperations<T, ID> deleteOperations;
    private CrudXMongoValidationHelper<T, ID> validationHelper;

    @PostConstruct
    @SuppressWarnings("unchecked")
    protected void init() {
        if (mongoTemplate == null) {
            throw new IllegalStateException(
                    "MongoTemplate not available. Add 'spring-boot-starter-data-mongodb' dependency.");
        }

        Type genericSuperclass = getClass().getGenericSuperclass();
        if (genericSuperclass instanceof ParameterizedType paramType) {
            Type[] typeArgs = paramType.getActualTypeArguments();
            if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                entityClass = (Class<T>) typeArgs[0];
                log.debug("Entity class: {}", entityClass.getSimpleName());
            }
        }

        if (entityClass == null) {
            Class<?>[] typeArgs = GenericTypeResolver.resolveTypeArguments(getClass(), CrudXMongoService.class);
            if (typeArgs != null && typeArgs.length > 0) {
                entityClass = (Class<T>) typeArgs[0];
            }
        }

        if (entityClass == null) {
            throw new IllegalStateException("Could not resolve entity class: " + getClass().getSimpleName());
        }

        initializeComponents();

        log.info("✅ MongoDB Service initialized: {} with modular architecture", entityClass.getSimpleName());
    }

    private void initializeComponents() {
        LocalValidatorFactoryBean validator = null;
        try {
            validator = new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();
            validator.afterPropertiesSet();
        } catch (Exception e) {
            log.warn("Could not initialize validator", e);
        }

        this.validationHelper = new CrudXMongoValidationHelper<>(validator);

        this.readOperations = new CrudXMongoReadOperations<>(mongoTemplate);

        this.updateOperations = new CrudXMongoUpdateOperations<>(
                mongoTemplate,
                validator,
                validationHelper,
                readOperations
        );


        this.deleteOperations = new CrudXMongoDeleteOperations<>(
                mongoTemplate,
                readOperations
        );

        this.batchOperations = new CrudXMongoBatchOperations<>(
                mongoTemplate,
                validationHelper
        );
    }

    @Override
    @Transactional(timeout = 300)
    public T create(T entity) {
        long start = System.currentTimeMillis();
        log.debug("Creating entity: {}", entityClass.getSimpleName());

        if (validationHelper.violatesUniqueConstraints(entity, entityClass, mongoTemplate)) {
            throw new io.github.sachinnimbal.crudx.core.exception.DuplicateEntityException(
                    validationHelper.buildDuplicateMessage(entity, entityClass));
        }

        entity.onCreate();
        T saved = mongoTemplate.save(entity);

        log.info("Entity created: {} in {} ms", saved.getId(), System.currentTimeMillis() - start);
        return saved;
    }

    @Override
    public BatchResult<T> createBatch(List<T> entities, boolean skipDuplicates) {
        return batchOperations.createBatch(entities, skipDuplicates, entityClass);
    }

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

    @Override
    public T update(ID id, Map<String, Object> updates) {
        return updateOperations.update(id, updates, entityClass);
    }

    @Override
    public BatchResult<T> updateBatch(Map<ID, Map<String, Object>> updates) {
        return updateOperations.updateBatch(updates, entityClass);
    }

    @Override
    public T delete(ID id) {
        return deleteOperations.delete(id, entityClass);
    }

    @Override
    public BatchResult<T> deleteBatch(List<ID> ids) {
        return deleteOperations.deleteBatch(ids, entityClass);
    }
}