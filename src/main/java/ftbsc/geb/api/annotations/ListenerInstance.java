package ftbsc.geb.api.annotations;

import ftbsc.geb.api.IListener;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation should mark either be a static instance of {@link IListener}
 * or a static method returning one.
 * @since 0.1.0
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.CLASS)
public @interface ListenerInstance {}
