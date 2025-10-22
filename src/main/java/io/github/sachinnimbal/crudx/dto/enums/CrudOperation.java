package io.github.sachinnimbal.crudx.dto.enums;

/**
 * CRUD operations supported by CrudX framework.
 * Used to determine which DTO applies to which endpoint.
 *
 * @author CrudX Framework
 * @since 1.0.0
 */
public enum CrudOperation {

    /**
     * POST /entity - Create single entity
     * Request DTO → Entity
     * Response: Entity → Response DTO
     */
    CREATE,

    /**
     * POST /entity/batch - Create multiple entities
     * Request DTO List → Entity List
     * Response: Entity List → Response DTO List
     */
    BATCH_CREATE,

    /**
     * GET /entity/{id} - Get entity by ID
     * Response: Entity → Response DTO
     */
    GET_ID,

    /**
     * GET /entity - Get all entities
     * Response: Entity List → Response DTO List
     */
    GET_ALL,

    /**
     * GET /entity/paged - Get paginated entities
     * Response: Page<Entity> → Page<Response DTO>
     */
    GET_PAGED,

    /**
     * PATCH /entity/{id} - Update entity
     * Request DTO → Entity (partial update)
     * Response: Entity → Response DTO
     */
    UPDATE,

    /**
     * DELETE /entity/{id} - Delete entity
     * Response: Entity → Response DTO (before deletion)
     */
    DELETE,

    /**
     * DELETE /entity/batch - Delete multiple entities
     * Response: Entity List → Response DTO List
     */
    BATCH_DELETE,

    /**
     * GET /entity/count - Count entities
     * Response: Long (no DTO)
     */
    COUNT,

    /**
     * GET /entity/exists/{id} - Check existence
     * Response: Boolean (no DTO)
     */
    EXISTS;

    /**
     * Check if operation requires request DTO.
     *
     * @return true if operation accepts request body
     */
    public boolean isRequestOperation() {
        return this == CREATE || this == BATCH_CREATE || this == UPDATE;
    }

    /**
     * Check if operation returns response DTO.
     *
     * @return true if operation returns entity data
     */
    public boolean isResponseOperation() {
        return this != COUNT && this != EXISTS;
    }

    /**
     * Check if operation handles batch/list.
     *
     * @return true for batch operations
     */
    public boolean isBatchOperation() {
        return this == BATCH_CREATE || this == BATCH_DELETE ||
                this == GET_ALL || this == GET_PAGED;
    }
}

