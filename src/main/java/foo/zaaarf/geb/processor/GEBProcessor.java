package foo.zaaarf.geb.processor;

import com.squareup.javapoet.*;
import foo.zaaarf.geb.api.IEvent;
import foo.zaaarf.geb.api.IEventCancelable;
import foo.zaaarf.geb.api.IEventDispatcher;
import foo.zaaarf.geb.api.IListener;
import foo.zaaarf.geb.api.annotations.Inherit;
import foo.zaaarf.geb.api.annotations.Listen;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * GEB's {@link javax.annotation.processing.Processor annotation processor},
 * which takes care of generating the {@link IEventDispatcher dispatchers}.
 */
@SupportedOptions(GEBProcessor.GEB_OUTPUT_PACKAGE)
@SupportedAnnotationTypes({
	"foo.zaaarf.geb.api.annotations.Listen",
	"foo.zaaarf.geb.api.annotations.Inherit"
})
public class GEBProcessor extends AbstractProcessor {
	/**
	 * The constant for the option key.
	 */
	static final String GEB_OUTPUT_PACKAGE = "gebOutputPackage";

	/**
	 * A {@link Map} tying each event class to a {@link Set} of listeners.
	 */
	private final Map<TypeMirror, Set<ListenerContainer>> listenerMap = new HashMap<>();

	/**
	 * A {@link Set} containing the fully-qualified names of the generated classes.
	 */
	private final Set<String> generatedClasses = new HashSet<>();

	/**
	 * A {@link TypeMirror} representing the {@link IListener} interface.
	 */
	private TypeMirror listenerInterface;

	/**
	 * A {@link TypeMirror} representing the {@link IEvent} interface.
	 */
	private TypeMirror eventInterface;

	/**
	 * A {@link TypeMirror} representing the {@link IEventCancelable} interface.
	 */
	private TypeMirror cancelableEventInterface;

	/**
	 * A {@link TypeElement} representing the {@link IEventDispatcher} interface.
	 */
	private TypeElement dispatcherInterface;

	/**
	 * Default constructor that doesn't need to do anything special.
	 */
	public GEBProcessor() {}

	@Override
	public synchronized void init(ProcessingEnvironment env) {
		super.init(env);
		this.listenerInterface = env.getElementUtils()
			.getTypeElement("foo.zaaarf.geb.api.IListener").asType();
		this.eventInterface = env.getElementUtils()
			.getTypeElement("foo.zaaarf.geb.api.IEvent").asType();
		this.dispatcherInterface = env.getElementUtils()
			.getTypeElement("foo.zaaarf.geb.api.IEventDispatcher");
		this.cancelableEventInterface = env.getElementUtils()
			.getTypeElement("foo.zaaarf.geb.api.IEventCancelable").asType();
	}

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
		boolean claimed = false;
		for(TypeElement ann : annotations) {
			if(ann.getQualifiedName().contentEquals(Listen.class.getName())) {
				claimed = true;
				for(Element e : env.getElementsAnnotatedWith(ann)) {
					Element enclosing = e.getEnclosingElement();
					if(enclosing.getAnnotation(Inherit.class) == null) {
						// prevent @Inherit classes from being processed twice
						this.processListener((ExecutableElement) e, e.getEnclosingElement());
					}
				}
			} else if(ann.getQualifiedName().contentEquals(Inherit.class.getName())) {
				claimed = true;
				for(Element e : env.getElementsAnnotatedWith(ann)) {
					this.processInheritance((TypeElement) e);
				}
			}
		}

		if(claimed && !this.listenerMap.isEmpty()) {
			this.generateClasses();
			this.generateServiceProvider();
		}

		return claimed;
	}

	/**
	 * Sets the supported source version to the latest one.
	 * It's either that or constant warnings, and the processor is simple enough.
	 * @return the latest source version
	 */
	@Override
	public SourceVersion getSupportedSourceVersion() {
		return SourceVersion.latest();
	}

	/**
	 * Verifies that the annotated method is valid and, if it is, adds it to
	 * the list. See the annotation's javadoc for details on what's considered
	 * a valid listener.
	 * @param listener the {@link ExecutableElement} that was annotated with {@link Listen}
	 * @param parent the {@link Element} to treat as parent of ths method
	 * @see Listen
	 */
	private void processListener(ExecutableElement listener, Element parent) {
		// if the method is not static:
		if(!listener.getModifiers().contains(Modifier.STATIC)) {
			TypeMirror parentType = parent.asType();

			// ensure the parent is an instance of IListener
			if(!this.processingEnv.getTypeUtils().isAssignable(parentType, this.listenerInterface)) {
				this.processingEnv.getMessager().printMessage(
					Diagnostic.Kind.ERROR,
					String.format(
						"[GEB] The parent of %s::%s does not implement the IListener interface!",
						parent.getSimpleName(),
						listener.getSimpleName()
					),
					listener
				);

				return;
			}

			// ensure the parent is not abstract
			if(parent.getModifiers().contains(Modifier.ABSTRACT)) {
				// no need to error out, just ignore this case
				// as it might have inheritors
				return;
			}
		}

		// ensure the listener method has only one parameter
		List<? extends VariableElement> params = listener.getParameters();
		if(listener.getParameters().size() != 1) {
			this.processingEnv.getMessager().printMessage(
				Diagnostic.Kind.ERROR,
				String.format(
					"[GEB] Method %s::%s: had %d arguments, expected 1!",
					parent.getSimpleName(),
					listener.getSimpleName(),
					params.size()
				),
				listener
			);

			return;
		}

		// ensure said parameter implements IEvent
		TypeMirror event = params.get(0).asType();
		if(!this.processingEnv.getTypeUtils().isAssignable(event, this.eventInterface)) {
			this.processingEnv.getMessager().printMessage(
				Diagnostic.Kind.ERROR,
				String.format(
					"[GEB] The parameter %s of %s::%s does not implement the IEvent interface!",
					parent.getSimpleName(),
					listener.getSimpleName(),
					params.get(0).getSimpleName()
				),
				listener
			);

			return;
		}

		// warn about return type
		if(!listener.getReturnType().getKind().equals(TypeKind.VOID)) {
			this.processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, String.format(
				"The method %s::%s has a return type: please note that it will be ignored.",
				parent.getSimpleName(),
				listener.getSimpleName()
			));
		}

		this.listenerMap.computeIfAbsent(event, k -> new HashSet<>())
			.add(new ListenerContainer(listener, parent.asType()));
	}

	/**
	 * Processes classes annotated with {@link Inherit}.
	 * @param inherited the class annotated with {@link Inherit}
	 */
	private void processInheritance(TypeElement inherited) {
		TypeMirror cur = inherited.asType();
		while(cur.getKind() == TypeKind.DECLARED) {
			TypeElement curElement = (TypeElement) this.processingEnv.getTypeUtils().asElement(cur);

			for(Element e : curElement.getEnclosedElements()) {
				Listen listenAnn = e.getAnnotation(Listen.class);
				if(listenAnn != null && (curElement == inherited || (
						!e.getModifiers().contains(Modifier.STATIC)
						&& listenAnn.inheritable()
				))) {
					this.processListener((ExecutableElement) e, inherited);
				}
			}

			cur = curElement.getSuperclass();
		}
	}

	/**
	 * Uses JavaPoet to generate the classes dispatcher classes.
	 */
	private void generateClasses() {
		this.listenerMap.forEach((event, listeners) -> {
			TypeElement eventClass = (TypeElement) this.processingEnv.getTypeUtils().asElement(event);
			boolean cancelable = this.processingEnv.getTypeUtils().isAssignable(event, this.cancelableEventInterface);
			ClassName setName = ClassName.get("java.util", "Set");

			ParameterSpec eventParam = ParameterSpec.builder(TypeName.get(event), "event").build();
			ParameterSpec listenersParam = ParameterSpec.builder(
				ParameterizedTypeName.get(
					ClassName.get("java.util", "Map"),
					ParameterizedTypeName.get(
						ClassName.get("java.lang", "Class"),
						WildcardTypeName.subtypeOf(TypeName.get(this.listenerInterface))
					),
					ParameterizedTypeName.get(
						setName,
						ClassName.get(this.listenerInterface)
					)
				),
				"listeners"
			).build();

			MethodSpec.Builder callListenersBuilder = MethodSpec.methodBuilder("callListeners")
				.addModifiers(Modifier.PUBLIC)
				.addAnnotation(Override.class)
				.addParameter(eventParam)
				.addParameter(listenersParam)
				.returns(boolean.class);

			// reorder the injectors to follow priority
			Map<TypeMirror, Integer> done = new HashMap<>();
			List<ListenerContainer> ordered = listeners.stream().sorted(
				Comparator.comparingInt(
					container -> ((ListenerContainer) container).annotation.priority()
				).reversed()
			).collect(Collectors.toList());

			// get all the relevant injectors
			for(int i = 0; i < ordered.size(); i++) {
				ListenerContainer listener = ordered.get(i);
				if(!done.containsKey(listener.parent)) {
					done.put(listener.parent, i);
					if(!listener.method.getModifiers().contains(Modifier.STATIC)) {
						String varName = String.format("listener%d", i);
						callListenersBuilder.addStatement(
							"$T<$T> $L = $N.get($T.class)", // Set is already imported per the parameters
							setName,
							this.listenerInterface,
							varName,
							listenersParam,
							this.processingEnv.getTypeUtils().erasure(listener.parent)
						);
					}
				}
			}

			for(ListenerContainer listener : ordered) {
				if(listener.method.getModifiers().contains(Modifier.STATIC)) {
					// if static call it directly
					callListenersBuilder.addStatement(
						"$T.$L($N)",
						listener.parent,
						listener.method.getSimpleName().toString(),
						eventParam
					);
				} else {
					// else iterate over its listeners
					String varName = String.format("listener%d", done.get(listener.parent));
					callListenersBuilder
						.addCode(
							CodeBlock.builder()
								.add("\n")
								.beginControlFlow("if($L != null)", varName)
								.beginControlFlow("for($T l : $L)", this.listenerInterface, varName)
								.addStatement(
									"(($T) l).$L($N)",
									this.processingEnv.getTypeUtils().erasure(listener.parent),
									listener.method.getSimpleName().toString(),
									eventParam
								)
								.endControlFlow()
								.endControlFlow()
								.add("\n")
								.build()
						);
				}
				if(cancelable) {
					callListenersBuilder.addStatement(
						"if($N.isCanceled()) return false",
						eventParam
					);
				}
			}

			callListenersBuilder.addStatement("return true");

			TypeMirror erasedEvent = this.processingEnv.getTypeUtils().erasure(event);
			MethodSpec eventType = MethodSpec.methodBuilder("eventType")
				.addModifiers(Modifier.PUBLIC)
				.addAnnotation(Override.class)
				.returns(ParameterizedTypeName.get(Class.class))
				.addStatement("return $T.class", erasedEvent)
				.build();

			TypeElement cursor = eventClass;
			StringBuilder realName = new StringBuilder(eventClass.getSimpleName().toString());
			while(cursor.getEnclosingElement() instanceof TypeElement) {
				cursor = (TypeElement) cursor.getEnclosingElement();
				realName.insert(0, '$');
				realName.insert(0, cursor.getSimpleName());
			}

			String clazzName = String.format("%sDispatcher", realName);

			TypeSpec clazz = TypeSpec.classBuilder(clazzName)
				.addModifiers(Modifier.PUBLIC)
				.addAnnotation(
					AnnotationSpec.builder(SuppressWarnings.class) // prevent warning spam
						.addMember("value" , "{$S, $S}", "unchecked", "rawtypes")
						.build()
				)
				.addSuperinterface(ParameterizedTypeName.get(
					ClassName.get(this.dispatcherInterface),
					TypeName.get(event)
				))
				.addMethod(callListenersBuilder.build())
				.addMethod(eventType)
				.build();

			// package name if specified, or falls back on "wherever the event was"
			// only complex environments should ever really need to specify it
			String packageName = this.processingEnv.getOptions().get(GEB_OUTPUT_PACKAGE);
			if(packageName == null) {
				packageName = this.processingEnv.getElementUtils().getPackageOf(
					this.processingEnv.getTypeUtils().asElement(event)
				).getQualifiedName().toString();
			}

			JavaFile javaFile = JavaFile.builder(packageName, clazz).build();
			String resultingClassName = String.format("%s.%s", packageName, clazzName);

			try {
				JavaFileObject injectorFile = this.processingEnv.getFiler().createSourceFile(resultingClassName);
				PrintWriter out = new PrintWriter(injectorFile.openWriter());
				javaFile.writeTo(out);
				out.close();
			} catch(IOException e) {
				this.processingEnv.getMessager().printMessage(
					Diagnostic.Kind.ERROR,
					String.format(
						"[GEB] An error occurred while generating class \"%s\": %s.\n%s",
						resultingClassName,
						e.getMessage(),
						stacktraceToString(e)
					)
				);
			}

			this.generatedClasses.add(resultingClassName);
		});
	}

	/**
	 * Generates the Service Provider file for the dispatchers.
	 */
	public void generateServiceProvider() {
		try {
			FileObject serviceProvider = processingEnv.getFiler().createResource(
				StandardLocation.CLASS_OUTPUT,
				"",
				"META-INF/services/foo.zaaarf.geb.api.IEventDispatcher"
			);

			PrintWriter out = new PrintWriter(serviceProvider.openWriter());
			this.generatedClasses.forEach(out::println);
			out.close();
		} catch(IOException e) {
			this.processingEnv.getMessager().printMessage(
				Diagnostic.Kind.ERROR,
				String.format(
					"[GEB] An error occurred while generating the service provider file: %s.\n%s",
					e.getMessage(),
					stacktraceToString(e)
				)
			);
		}
	}

	/**
	 * Puts a {@link Throwable}'s stacktrace into a string.
	 * @param t the throwable to get the stacktrace for
	 * @return the stacktrace as string
	 */
	public static String stacktraceToString(Throwable t) {
		StringWriter sw = new StringWriter();
		t.printStackTrace(new PrintWriter(sw));
		return sw.toString();
	}

	/**
	 * A container class to carry information about a listener class.
	 */
	private static class ListenerContainer {
		/**
		 * The actual listener, the annotated method.
		 */
		public final ExecutableElement method;

		/**
		 * The parent which implements {@link IListener}.
		 */
		public final TypeMirror parent;

		/**
		 * The {@link Listen} annotation on the method.
		 */
		public final Listen annotation;

		/**
		 * The public constructor.
		 * @param method the annotated method, assumed to be valid
		 *               and already checked
		 * @param parent the parent to call this on, which is assumed
		 *               to be able to access the given method
		 */
		public ListenerContainer(ExecutableElement method, TypeMirror parent) {
			this.method = method;
			this.parent = parent;
			this.annotation = method.getAnnotation(Listen.class);
		}
	}
}
