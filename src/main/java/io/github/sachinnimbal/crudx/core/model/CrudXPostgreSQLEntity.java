package io.github.sachinnimbal.crudx.core.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;


/**
 * @author Sachin Nimbal
 * @version 1.0.0
 * @since 2025
 * @Contact: <a href="mailto:sachinnimbal9@gmail.com">sachinnimbal9@gmail.com</a>
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Data
@MappedSuperclass
@EqualsAndHashCode(callSuper = false)
public abstract class CrudXPostgreSQLEntity<ID extends Serializable> extends CrudXBaseEntity<ID> {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private ID id;

    @Embedded
    private CrudXAudit audit = new CrudXAudit();

    @PrePersist
    public void onCreate() {
        if (audit == null) {
            audit = new CrudXAudit();
        }
        audit.onCreate();
    }

    @PreUpdate
    public void onUpdate() {
        if (audit == null) {
            audit = new CrudXAudit();
        }
        audit.onUpdate();
    }
}
