package io.github.sachinnimbal.crudx.service.impl.mongo;

import io.github.sachinnimbal.crudx.core.exception.EntityNotFoundException;
import io.github.sachinnimbal.crudx.core.model.CrudXMongoEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CrudXMongoReadOperations<T extends CrudXMongoEntity<ID>, ID extends Serializable> {

    private final MongoTemplate mongoTemplate;

    private static final int MAX_IN_MEMORY_THRESHOLD = 5000;
    private static final int STREAM_FETCH_SIZE = 500;

    public T findById(ID id, Class<T> entityClass) {
        T entity = mongoTemplate.findById(id, entityClass);
        if (entity == null) {
            throw new EntityNotFoundException(entityClass.getSimpleName(), id);
        }
        return entity;
    }

    public List<T> findAll(Class<T> entityClass) {
        long totalCount = count(entityClass);

        if (totalCount > MAX_IN_MEMORY_THRESHOLD) {
            log.warn("Large dataset ({} records) - using streaming", totalCount);
            return findAllStreaming(null, entityClass);
        }

        return mongoTemplate.findAll(entityClass);
    }

    public List<T> findAll(Sort sort, Class<T> entityClass) {
        long totalCount = count(entityClass);

        if (totalCount > MAX_IN_MEMORY_THRESHOLD) {
            return findAllStreaming(sort, entityClass);
        }

        Query query = new Query().with(sort);
        return mongoTemplate.find(query, entityClass);
    }

    public Page<T> findAll(Pageable pageable, Class<T> entityClass) {
        Query query = new Query().with(pageable);
        List<T> content = mongoTemplate.find(query, entityClass);
        long total = mongoTemplate.count(new Query(), entityClass);
        return new PageImpl<>(content, pageable, total);
    }

    public long count(Class<T> entityClass) {
        return mongoTemplate.count(new Query(), entityClass);
    }

    public boolean existsById(ID id, Class<T> entityClass) {
        return mongoTemplate.findById(id, entityClass) != null;
    }

    private List<T> findAllStreaming(Sort sort, Class<T> entityClass) {
        List<T> result = new ArrayList<>();
        int skip = 0;

        while (true) {
            Query query = new Query().skip(skip).limit(STREAM_FETCH_SIZE);
            if (sort != null) query.with(sort);

            List<T> batch = mongoTemplate.find(query, entityClass);
            if (batch.isEmpty()) break;

            result.addAll(batch);
            skip += STREAM_FETCH_SIZE;

            if (batch.size() < STREAM_FETCH_SIZE) break;
        }

        return result;
    }
}