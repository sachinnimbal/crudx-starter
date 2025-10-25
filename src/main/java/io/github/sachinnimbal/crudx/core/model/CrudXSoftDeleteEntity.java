package io.github.sachinnimbal.crudx.core.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@MappedSuperclass
@EqualsAndHashCode(callSuper = true)
public abstract class CrudXSoftDeleteEntity<ID extends Serializable>
        extends CrudXBaseEntity<ID> {

    @Column(name = "deleted_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime deletedAt;

    @Column(name = "is_deleted")
    private Boolean deleted = false;

    @Column(name = "deleted_by")
    private String deletedBy;

    public void softDelete() {
        this.deleted = true;
        this.deletedAt = LocalDateTime.now();
    }

    public void restore() {
        this.deleted = false;
        this.deletedAt = null;
        this.deletedBy = null;
    }

    @PrePersist
    public void prePersist() {
        if (deleted == null) {
            deleted = false;
        }
    }
}
