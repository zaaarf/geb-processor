package ftbsc.geb.api.annotations;

import ftbsc.geb.api.IEvent;
import ftbsc.geb.api.IListener;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the method as a listener. Its parent must implement the {@link IListener} interface.
 * The annotated method should only take a single input value, an instance of {@link IEvent};
 * it should be either void or boolean; if it's boolean, the return value indicates whether
 * the event was canceled.
 * @since 0.1.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface Listen {
	/**
	 * @return an integer indicating priority level for the listener, defaulting to 0
	 */
	int priority() default 0;

	/**
	 * @return an array of {@link String}s specifying which buses they should be listening on;
	 * 				 an empty array means that they should listen on all buses, ignoring identifiers:
	 * 				 that's probably what you wanted anyway.
	 */
	String[] on() default {}; //empty array = listen on all of them
}
