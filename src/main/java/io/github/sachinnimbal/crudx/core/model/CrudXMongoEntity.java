package io.github.sachinnimbal.crudx.core.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.data.annotation.Id;

import java.io.Serializable;


/**
 * @author Sachin Nimbal
 * @version 1.0.0
 * @since 2025
 * @Contact: <a href="mailto:sachinnimbal9@gmail.com">sachinnimbal9@gmail.com</a>
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Data
@EqualsAndHashCode(callSuper = false)
public abstract class CrudXMongoEntity<ID extends Serializable> extends CrudXBaseEntity<ID> {

    @Id
    private ID id;

    private CrudXAudit audit = new CrudXAudit();

    public void onCreate() {
        if (audit == null) {
            audit = new CrudXAudit();
        }
        audit.onCreate();
    }

    public void onUpdate() {
        if (audit == null) {
            audit = new CrudXAudit();
        }
        audit.onUpdate();
    }
}