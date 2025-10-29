package io.github.sachinnimbal.crudx.web.components;

import io.github.sachinnimbal.crudx.core.model.CrudXBaseEntity;
import io.github.sachinnimbal.crudx.core.response.PageResponse;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Lifecycle hook methods for CrudXController
 * All methods are empty by default - override as needed
 */
public abstract class CrudXLifecycleHooks<T extends CrudXBaseEntity<ID>, ID extends Serializable> {

    // ==================== CREATE HOOKS ====================

    /**
     * Called before creating a single entity
     * @param entity The entity to be created
     */
    protected void beforeCreate(T entity) {
    }

    /**
     * Called after successfully creating a single entity
     * @param entity The created entity with generated ID
     */
    protected void afterCreate(T entity) {
    }

    /**
     * Called before batch creation
     * @param entities List of entities to be created
     */
    protected void beforeCreateBatch(List<T> entities) {
    }

    /**
     * Called after successful batch creation
     * @param entities List of successfully created entities
     */
    protected void afterCreateBatch(List<T> entities) {
    }

    // ==================== UPDATE HOOKS ====================

    /**
     * Called before updating an entity
     * @param id The ID of the entity being updated
     * @param updates Map of field updates
     * @param existingEntity The current state of the entity
     */
    protected void beforeUpdate(ID id, Map<String, Object> updates, T existingEntity) {
    }

    /**
     * Called after successfully updating an entity
     * @param updatedEntity The updated entity
     * @param oldEntity The previous state of the entity (may be null)
     */
    protected void afterUpdate(T updatedEntity, T oldEntity) {
    }

    // ==================== DELETE HOOKS ====================

    /**
     * Called before deleting an entity
     * @param id The ID of the entity being deleted
     * @param deletedEntity The entity about to be deleted
     */
    protected void beforeDelete(ID id, T deletedEntity) {
    }

    /**
     * Called after successfully deleting an entity
     * @param id The ID of the deleted entity
     * @param deletedEntity The deleted entity
     */
    protected void afterDelete(ID id, T deletedEntity) {
    }

    /**
     * Called before batch deletion
     * @param ids List of IDs to be deleted
     */
    protected void beforeDeleteBatch(List<ID> ids) {
    }

    /**
     * Called after successful batch deletion
     * @param deletedIds List of successfully deleted IDs
     */
    protected void afterDeleteBatch(List<ID> deletedIds) {
    }

    // ==================== READ HOOKS ====================

    /**
     * Called after finding an entity by ID
     * @param entity The found entity
     */
    protected void afterFindById(T entity) {
    }

    /**
     * Called after finding all entities
     * @param entities List of all entities
     */
    protected void afterFindAll(List<T> entities) {
    }

    /**
     * Called after finding paged entities
     * @param pageResponse The page response containing entities
     */
    protected void afterFindPaged(PageResponse<T> pageResponse) {
    }
}