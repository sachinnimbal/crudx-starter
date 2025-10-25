package io.github.sachinnimbal.crudx.core.model;

import lombok.Data;

import java.io.Serializable;

@Data
public abstract class CrudXBaseEntity<ID extends Serializable> implements Serializable {
    public abstract ID getId();
    public abstract void setId(ID id);
}
