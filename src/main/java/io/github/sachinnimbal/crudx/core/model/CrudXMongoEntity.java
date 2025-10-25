package io.github.sachinnimbal.crudx.core.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.data.annotation.Id;

import java.io.Serializable;

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
