package ftbsc.geb.api.annotations;

import ftbsc.geb.api.IEvent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as an Event. It should implement the {@link IEvent} interface.
 * It doesn't need to be abstract, but it can never be final.
 * @since 0.1.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface Event {}
