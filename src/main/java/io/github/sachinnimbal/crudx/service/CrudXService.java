package io.github.sachinnimbal.crudx.service;

import io.github.sachinnimbal.crudx.core.model.CrudXBaseEntity;
import io.github.sachinnimbal.crudx.core.response.BatchResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.io.Serializable;
import java.util.List;
import java.util.Map;


/**
 * @author Sachin Nimbal
 * @version 1.0.0
 * @since 2025
 * @Contact: <a href="mailto:sachinnimbal9@gmail.com">sachinnimbal9@gmail.com</a>
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
public interface CrudXService<T extends CrudXBaseEntity<ID>, ID extends Serializable> {

    T create(T entity);

    BatchResult<T> createBatch(List<T> entities, boolean skipDuplicates);

    T findById(ID id);

    List<T> findAll();

    List<T> findAll(Sort sort);

    Page<T> findAll(Pageable pageable);

    T update(ID id, Map<String, Object> updates);

    void delete(ID id);

    long count();

    boolean existsById(ID id);

    BatchResult<ID> deleteBatch(List<ID> ids);
}