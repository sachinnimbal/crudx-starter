package io.github.sachinnimbal.crudx.service.impl.sql;

import io.github.sachinnimbal.crudx.core.model.CrudXBaseEntity;
import io.github.sachinnimbal.crudx.core.response.BatchResult;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Optimized SQL delete operations
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrudXSQLDeleteOperations<T extends CrudXBaseEntity<ID>, ID extends Serializable> {

    private final EntityManager entityManager;
    private final CrudXSQLReadOperations<T, ID> readOperations;

    private static final int DELETE_BATCH_SIZE = 50;

    @Transactional
    public T delete(ID id, Class<T> entityClass) {
        T entity = readOperations.findById(id, entityClass);
        entityManager.remove(entity);
        entityManager.flush();
        return entity;
    }

    @Transactional
    public BatchResult<T> deleteBatch(List<ID> ids, Class<T> entityClass) {
        int deleted = 0;
        int notFound = 0;
        List<String> skipReasons = new ArrayList<>();

        for (int i = 0; i < ids.size(); i += DELETE_BATCH_SIZE) {
            int end = Math.min(i + DELETE_BATCH_SIZE, ids.size());
            List<ID> batch = ids.subList(i, end);

            for (ID id : batch) {
                try {
                    T entity = entityManager.find(entityClass, id);
                    if (entity != null) {
                        entityManager.remove(entity);
                        deleted++;
                    } else {
                        notFound++;
                        if (skipReasons.size() < 1000) {
                            skipReasons.add("ID " + id + " not found");
                        }
                    }
                } catch (Exception e) {
                    notFound++;
                    if (skipReasons.size() < 1000) {
                        skipReasons.add("ID " + id + ": " + e.getMessage());
                    }
                }
            }

            entityManager.flush();
            entityManager.clear();
        }

        BatchResult<T> result = new BatchResult<>();
        result.setCreatedEntities(Collections.emptyList());
        result.setSkippedCount(notFound);
        result.setSkippedReasons(skipReasons);
        return result;
    }
}