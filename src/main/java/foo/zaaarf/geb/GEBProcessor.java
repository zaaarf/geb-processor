package foo.zaaarf.geb;

import com.squareup.javapoet.*;
import foo.zaaarf.geb.api.IEvent;
import foo.zaaarf.geb.api.IEventCancelable;
import foo.zaaarf.geb.api.IEventDispatcher;
import foo.zaaarf.geb.api.IListener;
import foo.zaaarf.geb.api.annotations.Inherit;
import foo.zaaarf.geb.api.annotations.Listen;
import foo.zaaarf.geb.exceptions.BadListenerArgumentsException;
import foo.zaaarf.geb.exceptions.MissingInterfaceException;

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
import java.util.*;
import java.util.stream.Collectors;

/**
 * GEB's {@link javax.annotation.processing.Processor annotation processor},
 * which takes care of generating the {@link IEventDispatcher dispatchers}.
 */
@SupportedAnnotationTypes({
	"foo.zaaarf.geb.api.annotations.Listen",
	"foo.zaaarf.geb.api.annotations.Inherit"
})
public class GEBProcessor extends AbstractProcessor {

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

	/**
	 * Initializes the processor with the given environment.
	 * Also takes carae of initializing the TypeMirror "constants" for later use.
	 * @param env the environment
	 */
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

	/**
	 * The starting point of the processor.
	 * It calls {@link #processListener(ExecutableElement, Element)} on all elements
	 * annotated with the {@link Listen} annotation, then ensures that all {@link Inherit}
	 * classes are also processed.
	 * @param annotations the annotation types requested to be processed
	 * @param env environment for information about the current and prior round
	 * @return whether the set of annotation types are claimed by this processor
	 */
	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
		boolean claimed = false;
		for(TypeElement ann : annotations) {
			if(ann.getQualifiedName().contentEquals(Listen.class.getName())) {
				claimed = true;
				for(Element e : env.getElementsAnnotatedWith(ann)) {
					this.processListener((ExecutableElement) e, e.getEnclosingElement());
				}
			} else if(ann.getQualifiedName().contentEquals(Inherit.class.getName())) {
				claimed = true;
				for(Element e : env.getElementsAnnotatedWith(ann)) {
					this.processInheritance((TypeElement) e);
				}
			}
		}

		if(!this.listenerMap.isEmpty()) {
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
		// if the method is not static, ensure the parent is an instance of IListener
		if(!listener.getModifiers().contains(Modifier.STATIC)) {
			TypeMirror parentType = parent.asType();
			if(!this.processingEnv.getTypeUtils().isAssignable(parentType, this.listenerInterface))
				throw new MissingInterfaceException(
					parent.getSimpleName().toString(),
					listener.getSimpleName().toString()
				);
		}

		// ensure the listener method has only one parameter
		List<? extends VariableElement> params = listener.getParameters();
		if(listener.getParameters().size() != 1) {
			throw new BadListenerArgumentsException.Count(
				parent.getSimpleName().toString(),
				listener.getSimpleName().toString(),
				params.size());
		}

		// ensure said parameter implements IEvent
		TypeMirror event = params.get(0).asType();
		if(!this.processingEnv.getTypeUtils().isAssignable(event, this.eventInterface)) {
			throw new BadListenerArgumentsException.Type(
				parent.getSimpleName().toString(),
				listener.getSimpleName().toString(),
				params.get(0).getSimpleName().toString());
		}

		// warn about return type
		if(!listener.getReturnType().getKind().equals(TypeKind.VOID)) {
			this.processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, String.format(
				"The method %s::%s has a return type: please note that it will be ignored.",
				parent.getSimpleName().toString(),
				listener.getSimpleName().toString()));
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
				if(listenAnn != null && !e.getModifiers().contains(Modifier.STATIC) && listenAnn.inheritable()) {
					this.processListener((ExecutableElement) e, curElement);
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
					String varName = String.format("listener%d", i);
					callListenersBuilder.addStatement(
						"$T<$T> $L = $N.get($T.class)", // Set is already imported per the parameters
						setName,
						this.listenerInterface,
						varName,
						listenersParam,
						listener.parent
					);
				}
			}

			for(ListenerContainer listener : ordered) {
				if(listener.method.getModifiers().contains(Modifier.STATIC)) {
					// if static call it directly
					callListenersBuilder.addStatement(
						"$T.$L($N);",
						listener.parent,
						listener.method.getSimpleName().toString(),
						eventParam
					);
				} else {
					// else iterate over its listeners
					String varName = String.format("listener%d", done.get(listener.parent));
					callListenersBuilder
						.addStatement("if($L != null) { for($T l : $L) {", varName, this.listenerInterface, varName)
						.addStatement(
							"if(l != null) (($T) l).$L($N); } }",
							listener.parent,
							listener.method.getSimpleName().toString(),
							eventParam
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
						.addMember("value" , "{$S, $S}", "unchecked", "rawtypes").build()
				)
				.addSuperinterface(ParameterizedTypeName.get(
					ClassName.get(this.dispatcherInterface),
					TypeName.get(event)
				))
				.addMethod(callListenersBuilder.build())
				.addMethod(eventType)
				.build();

			String packageName = "foo.zaaarf.geb.generated";
			JavaFile javaFile = JavaFile.builder(packageName, clazz).build();
			String resultingClassName = String.format("%s.%s", packageName, clazzName);

			try {
				JavaFileObject injectorFile = this.processingEnv.getFiler().createSourceFile(resultingClassName);
				PrintWriter out = new PrintWriter(injectorFile.openWriter());
				javaFile.writeTo(out);
				out.close();
			} catch(IOException e) {
				throw new RuntimeException(e);
			}

			this.generatedClasses.add(resultingClassName);
		});
	}

	/**
	 * Generates the Service Provider file for the dispatchers.
	 */
	private void generateServiceProvider() {
		try {
			FileObject serviceProvider = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "",
				"META-INF/services/foo.zaaarf.geb.api.IEventDispatcher");
			PrintWriter out = new PrintWriter(serviceProvider.openWriter());
			this.generatedClasses.forEach(out::println);
			out.close();
		} catch(IOException e) {
			throw new RuntimeException(e);
		}
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
		 */
		public ListenerContainer(ExecutableElement method) {
			this(method, method.getEnclosingElement().asType());
		}

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
