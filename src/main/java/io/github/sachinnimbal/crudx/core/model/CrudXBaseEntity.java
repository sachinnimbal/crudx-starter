package io.github.sachinnimbal.crudx.core.model;

import lombok.Data;

import java.io.Serializable;


/**
 * @author Sachin Nimbal
 * @version 1.0.0
 * @since 2025
 * @Contact: <a href="mailto:sachinnimbal9@gmail.com">sachinnimbal9@gmail.com</a>
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Data
public abstract class CrudXBaseEntity<ID extends Serializable> implements Serializable {
    public abstract ID getId();
    public abstract void setId(ID id);
}