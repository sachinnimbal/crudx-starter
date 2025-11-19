package io.github.sachinnimbal.crudx.service.impl.mongo;

import io.github.sachinnimbal.crudx.core.model.CrudXMongoEntity;
import io.github.sachinnimbal.crudx.core.response.BatchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class CrudXMongoDeleteOperations<T extends CrudXMongoEntity<ID>, ID extends Serializable> {

    private final MongoTemplate mongoTemplate;
    private final CrudXMongoReadOperations<T, ID> readOperations;

    private static final int DELETE_BATCH_SIZE = 50;

    public T delete(ID id, Class<T> entityClass) {
        Query query = new Query(Criteria.where("_id").is(id));
        T entity = mongoTemplate.findAndRemove(query, entityClass);
        if (entity == null) {
            throw new io.github.sachinnimbal.crudx.core.exception.EntityNotFoundException(
                    entityClass.getSimpleName(), id);
        }
        return entity;
    }

    public BatchResult<T> deleteBatch(List<ID> ids, Class<T> entityClass) {
        int deleted = 0;
        int notFound = 0;
        List<String> skipReasons = new ArrayList<>();

        for (int i = 0; i < ids.size(); i += DELETE_BATCH_SIZE) {
            int end = Math.min(i + DELETE_BATCH_SIZE, ids.size());
            List<ID> batch = ids.subList(i, end);

            Query findQuery = Query.query(Criteria.where("_id").in(batch));
            List<T> found = mongoTemplate.find(findQuery, entityClass);
            Set<ID> foundIds = found.stream().map(T::getId).collect(Collectors.toSet());

            for (ID id : batch) {
                if (foundIds.contains(id)) {
                    deleted++;
                } else {
                    notFound++;
                    if (skipReasons.size() < 1000) {
                        skipReasons.add("ID " + id + " not found");
                    }
                }
            }

            if (!foundIds.isEmpty()) {
                Query deleteQuery = Query.query(Criteria.where("_id").in(foundIds));
                mongoTemplate.remove(deleteQuery, entityClass);
            }
        }

        BatchResult<T> result = new BatchResult<>();
        result.setCreatedEntities(Collections.emptyList());
        result.setSkippedCount(notFound);
        result.setSkippedReasons(skipReasons);
        return result;
    }
}
