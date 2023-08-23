package ftbsc.geb.exceptions;

/**
 * Thrown when there is something wrong with a listener method.
 */
public class BadListenerArgumentsException extends RuntimeException {

	/**
	 * The constructor. It's not meant to be used as is, refer to the subclasses
	 * @param message the message to pass along to the superclass
	 */
	protected BadListenerArgumentsException(String message) {
		super(message);
	}

	/**
	 * Thrown when the number of arguments is off.
	 */
	public static class Count extends BadListenerArgumentsException {

		/**
		 * The public constructor.
		 * @param clazz the fully-qualified name of the parent class
		 * @param method the annotated listener method
		 * @param count the number of arguments found
		 */
		public Count(String clazz, String method, int count) {
			super(String.format(
				"An error occurred while evaluating method %s::%s: found %d arguments, expected 1!",
				clazz, method, count));
		}
	}

	/**
	 * Thrown when the argument is of the wrong type.
	 */
	public static class Type extends BadListenerArgumentsException {

		/**
		 * The public constructor.
		 * @param clazz the fully-qualified name of the parent class
		 * @param method the annotated listener method
		 * @param parameterName the name of the parameter
		 */
		public Type(String clazz, String method, String parameterName) {
			super(String.format(
				"The parameter %s of %s::%s does not implement the IEvent interface!",
				parameterName, clazz, method
			));
		}
	}
}
