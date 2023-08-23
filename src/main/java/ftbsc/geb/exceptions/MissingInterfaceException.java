package ftbsc.geb.exceptions;

/**
 * Thrown when a parent of a listener method does not implement the
 * appropriate interface,
 */
public class MissingInterfaceException extends RuntimeException {

	/**
	 * The public constructor.
	 * @param clazz the fully-qualified name of the parent class
	 * @param method the annotated listener method
	 */
	public MissingInterfaceException(String clazz, String method) {
		super(String.format("The parent of %s::%s does not implement the IListener interface!", clazz, method));
	}
}
