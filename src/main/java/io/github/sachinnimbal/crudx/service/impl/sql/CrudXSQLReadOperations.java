package io.github.sachinnimbal.crudx.service.impl.sql;

import io.github.sachinnimbal.crudx.core.exception.EntityNotFoundException;
import io.github.sachinnimbal.crudx.core.model.CrudXBaseEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CrudXSQLReadOperations<T extends CrudXBaseEntity<ID>, ID extends Serializable> {

    private final EntityManager entityManager;

    private static final int MAX_IN_MEMORY_THRESHOLD = 5000;
    private static final int STREAM_FETCH_SIZE = 500;

    @Transactional(readOnly = true)
    public T findById(ID id, Class<T> entityClass) {
        T entity = entityManager.find(entityClass, id);
        if (entity == null) {
            throw new EntityNotFoundException(entityClass.getSimpleName(), id);
        }
        return entity;
    }

    @Transactional(readOnly = true)
    public List<T> findAll(Class<T> entityClass) {
        long totalCount = count(entityClass);

        if (totalCount > MAX_IN_MEMORY_THRESHOLD) {
            log.warn("Large dataset ({} records) - using streaming", totalCount);
            return findAllStreaming(null, entityClass);
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(entityClass);
        query.select(query.from(entityClass));

        return entityManager.createQuery(query).getResultList();
    }

    @Transactional(readOnly = true)
    public List<T> findAll(Sort sort, Class<T> entityClass) {
        long totalCount = count(entityClass);

        if (totalCount > MAX_IN_MEMORY_THRESHOLD) {
            return findAllStreaming(sort, entityClass);
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(entityClass);
        Root<T> root = query.from(entityClass);
        query.select(root);

        List<Order> orders = new ArrayList<>();
        sort.forEach(order -> {
            orders.add(order.isAscending() ? cb.asc(root.get(order.getProperty()))
                    : cb.desc(root.get(order.getProperty())));
        });
        query.orderBy(orders);

        return entityManager.createQuery(query).getResultList();
    }

    @Transactional(readOnly = true)
    public Page<T> findAll(Pageable pageable, Class<T> entityClass) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(entityClass);
        Root<T> root = query.from(entityClass);
        query.select(root);

        if (pageable.getSort().isSorted()) {
            List<Order> orders = new ArrayList<>();
            pageable.getSort().forEach(order -> {
                orders.add(order.isAscending() ? cb.asc(root.get(order.getProperty()))
                        : cb.desc(root.get(order.getProperty())));
            });
            query.orderBy(orders);
        }

        List<T> content = entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        long total = count(entityClass);
        return new PageImpl<>(content, pageable, total);
    }

    @Transactional(readOnly = true)
    public long count(Class<T> entityClass) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        query.select(cb.count(query.from(entityClass)));
        return entityManager.createQuery(query).getSingleResult();
    }

    @Transactional(readOnly = true)
    public boolean existsById(ID id, Class<T> entityClass) {
        return entityManager.find(entityClass, id) != null;
    }

    /**
     * Streaming read for large datasets
     */
    @Transactional(readOnly = true)
    private List<T> findAllStreaming(Sort sort, Class<T> entityClass) {
        List<T> result = new ArrayList<>();
        int offset = 0;

        while (true) {
            CriteriaBuilder cb = entityManager.getCriteriaBuilder();
            CriteriaQuery<T> query = cb.createQuery(entityClass);
            Root<T> root = query.from(entityClass);
            query.select(root);

            if (sort != null) {
                List<Order> orders = new ArrayList<>();
                sort.forEach(order -> {
                    orders.add(order.isAscending() ? cb.asc(root.get(order.getProperty()))
                            : cb.desc(root.get(order.getProperty())));
                });
                query.orderBy(orders);
            }

            TypedQuery<T> typedQuery = entityManager.createQuery(query);
            typedQuery.setFirstResult(offset);
            typedQuery.setMaxResults(STREAM_FETCH_SIZE);

            List<T> batch = typedQuery.getResultList();
            if (batch.isEmpty()) break;

            result.addAll(batch);
            offset += STREAM_FETCH_SIZE;
            entityManager.clear();

            if (batch.size() < STREAM_FETCH_SIZE) break;
        }

        return result;
    }
}