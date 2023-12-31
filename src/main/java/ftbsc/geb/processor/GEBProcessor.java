package ftbsc.geb.processor;

import com.squareup.javapoet.*;
import ftbsc.geb.api.IEvent;
import ftbsc.geb.api.IEventCancelable;
import ftbsc.geb.api.IEventDispatcher;
import ftbsc.geb.api.IListener;
import ftbsc.geb.api.annotations.Listen;
import ftbsc.geb.exceptions.BadListenerArgumentsException;
import ftbsc.geb.exceptions.MissingInterfaceException;

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
@SupportedAnnotationTypes("ftbsc.geb.api.annotations.Listen")
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
	 * A {@link TypeMirror} representing the {@link IEventDispatcher} interface.
	 */
	private TypeMirror dispatcherInterface;

	/**
	 * Initializes the processor with the given environment.
	 * Also takes carae of initializing the TypeMirror "constants" for later use.
	 * @param env the environment
	 */
	@Override
	public synchronized void init(ProcessingEnvironment env) {
		super.init(env);
		this.listenerInterface = env.getElementUtils()
			.getTypeElement("ftbsc.geb.api.IListener").asType();
		this.eventInterface = env.getElementUtils()
			.getTypeElement("ftbsc.geb.api.IEvent").asType();
		this.dispatcherInterface = env.getElementUtils()
			.getTypeElement("ftbsc.geb.api.IEventDispatcher").asType();
		this.cancelableEventInterface = env.getElementUtils()
			.getTypeElement("ftbsc.geb.api.IEventCancelable").asType();
	}

	/**
	 * The starting point of the processor.
	 * It calls {@link #processListener(Element)} on all elements annotated with
	 * the {@link Listen} annotation.
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
				for(Element e : env.getElementsAnnotatedWith(ann))
					this.processListener(e);
				if(!this.listenerMap.isEmpty()) {
					this.generateClasses();
					this.generateServiceProvider();
				}
			}
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
	 * @see Listen
	 * @param target the {@link Element} that was annotated with {@link Listen}
	 */
	private void processListener(Element target) {
		ExecutableElement listener = (ExecutableElement) target; //this cast will never fail

		//ensure the parent is instance of IListener
		TypeMirror parentType = listener.getEnclosingElement().asType();
		if(!this.processingEnv.getTypeUtils().isAssignable(parentType, this.listenerInterface))
			throw new MissingInterfaceException(
				listener.getEnclosingElement().getSimpleName().toString(),
				listener.getSimpleName().toString());

		//ensure the listener method has only one parameter
		List<? extends VariableElement> params = listener.getParameters();
		if(listener.getParameters().size() != 1)
			throw new BadListenerArgumentsException.Count(
				listener.getEnclosingElement().getSimpleName().toString(),
				listener.getSimpleName().toString(),
				params.size());

		//ensure said parameter implements IEvent
		TypeMirror event = params.get(0).asType();
		if(!this.processingEnv.getTypeUtils().isAssignable(event, this.eventInterface))
			throw new BadListenerArgumentsException.Type(
				listener.getEnclosingElement().getSimpleName().toString(),
				listener.getSimpleName().toString(),
				params.get(0).getSimpleName().toString());

		//warn about return type
		if(!listener.getReturnType().getKind().equals(TypeKind.VOID))
			this.processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, String.format(
				"The method %s::%s has a return type: please note that it will be ignored.",
				listener.getEnclosingElement().getSimpleName().toString(),
				listener.getSimpleName().toString()));

		if(!this.listenerMap.containsKey(event))
			this.listenerMap.put(event, new HashSet<>());
		this.listenerMap.get(event).add(new ListenerContainer(listener));
	}

	/**
	 * Uses JavaPoet to generate the classes dispatcher classes.
	 */
	private void generateClasses() {
		this.listenerMap.forEach((event, listeners) -> {
			TypeElement eventClass = (TypeElement) this.processingEnv.getTypeUtils().asElement(event);
			boolean cancelable = this.processingEnv.getTypeUtils().isAssignable(event, this.cancelableEventInterface);

			//reorder the injectors to follow priority
			List<ListenerContainer> ordered = listeners.stream().sorted(Comparator.comparingInt(
				container -> container.annotation.priority()
			)).collect(Collectors.toList());

			ParameterSpec eventParam = ParameterSpec.builder(TypeName.get(this.eventInterface), "event").build();
			ParameterSpec listenersParam = ParameterSpec.builder(ParameterizedTypeName.get(
				ClassName.get("java.util", "Map"), ParameterizedTypeName.get(
					ClassName.get("java.lang", "Class"),
					WildcardTypeName.subtypeOf(TypeName.get(this.listenerInterface))),
				ClassName.get(this.listenerInterface)), "listeners")
				.build();

			MethodSpec.Builder callListenersBuilder = MethodSpec.methodBuilder("callListeners")
				.addModifiers(Modifier.PUBLIC)
				.addAnnotation(Override.class)
				.addAnnotation(AnnotationSpec.builder(SuppressWarnings.class) //because why not
					.addMember("value" , "{$S}", "unchecked").build())
				.addParameter(eventParam)
				.addParameter(listenersParam)
				.returns(boolean.class);

			int counter = 0;
			for(ListenerContainer listener : ordered) {
				String varName = String.format("listener%d", counter);
				callListenersBuilder
					.addStatement("$T $L = $N.get($T.class)", this.listenerInterface, varName, listenersParam, listener.parent)
					.addStatement("if($L.isActive()) (($T) $L).$L(($T) $N)", varName, listener.parent, varName,
						listener.method.getSimpleName().toString(), event, eventParam);
				if(cancelable) callListenersBuilder
					.addStatement("if((($T) $N).isCanceled()) return true", this.cancelableEventInterface, eventParam);
				counter++;
			}

			callListenersBuilder.addStatement("return false");

			MethodSpec eventType = MethodSpec.methodBuilder("eventType")
				.addModifiers(Modifier.PUBLIC)
				.addAnnotation(Override.class)
				.returns(Class.class)
				.addStatement("return $T.class", event)
				.build();

			String clazzName = String.format("%sDispatcher", eventClass.getSimpleName());
			TypeSpec clazz = TypeSpec.classBuilder(clazzName)
				.addModifiers(Modifier.PUBLIC)
				.addSuperinterface(this.dispatcherInterface)
				.addMethod(callListenersBuilder.build())
				.addMethod(eventType)
				.build();

			String packageName = "ftbsc.geb.generated";
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
				"META-INF/services/ftbsc.geb.api.IEventDispatcher");
			PrintWriter out = new PrintWriter(serviceProvider.openWriter());
			this.generatedClasses.forEach(out::println);
			out.close();
		} catch(IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * A container class to carry information about a listener method.
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
			this.method = method;
			this.parent = method.getEnclosingElement().asType();
			this.annotation = method.getAnnotation(Listen.class);
		}
	}
}
