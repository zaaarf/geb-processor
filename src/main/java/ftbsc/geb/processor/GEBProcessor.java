package ftbsc.geb.processor;

import com.squareup.javapoet.*;
import ftbsc.geb.api.annotations.Listen;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;

/**
 *
 */
@SupportedAnnotationTypes({"ftbsc.geb.api.annotations.*"})
public class GEBProcessor extends AbstractProcessor {

	private final Map<TypeMirror, Set<ListenerContainer>> listenerMap = new HashMap<>();

	private final Set<String> generatedClasses = new HashSet<>();

	@Override
	public boolean process(Set<? extends TypeElement> set, RoundEnvironment env) {
		for(TypeElement ann : set)
			if(ann.getQualifiedName().contentEquals(Listen.class.getName()))
				for(Element e : env.getElementsAnnotatedWith(ann))
					this.processListener(e);
		if(!this.listenerMap.isEmpty()) {
			this.generateClasses();
			this.generateServiceProvider();
			return true;
		} else return false;
	}

	private final TypeMirror listenerInterface = this.processingEnv.getElementUtils()
		.getTypeElement("ftbsc.geb.api.IListener").asType();

	private final TypeMirror eventInterface = this.processingEnv.getElementUtils()
		.getTypeElement("ftbsc.geb.api.IEvent").asType();

	private final TypeMirror dispatcherInterface = this.processingEnv.getElementUtils()
		.getTypeElement("ftbsc.geb.api.IEventDispatcher").asType();

	private void processListener(Element target) {
		ExecutableElement listener = (ExecutableElement) target; //this cast will never fail

		//ensure the parent is instance of IListener
		TypeMirror parentType = listener.getEnclosingElement().asType();
		if(!this.processingEnv.getTypeUtils().isAssignable(parentType, this.listenerInterface))
			return; //TODO throw error, parent doesn't implement the interface

		//ensure the listener method has only a single IEvent parameter
		List<? extends VariableElement> params = listener.getParameters();
		if(listener.getParameters().size() != 1)
			return; //TODO throw error, bad parameter amount
		TypeMirror event = params.get(0).asType();
		if(!this.processingEnv.getTypeUtils().isAssignable(event, this.eventInterface))
			return; //TODO throw error, bad parameter type

		if(!this.listenerMap.containsKey(event))
			this.listenerMap.put(event, new HashSet<>());
		this.listenerMap.get(event).add(new ListenerContainer(listener));
	}

	private void generateClasses() {
		this.listenerMap.forEach((event, listeners) -> {
			TypeElement eventClass = (TypeElement) this.processingEnv.getTypeUtils().asElement(event);

			//reorder the injectors to follow priority
			List<ListenerContainer> ordered = listeners.stream().sorted(Comparator.comparingInt(
				container -> container.annotation.priority()
			)).collect(Collectors.toList());

			ParameterSpec eventParam = ParameterSpec.builder(TypeName.get(this.eventInterface), "event").build();
			ParameterSpec listenersParam = ParameterSpec.builder(ParameterizedTypeName.get(
				ClassName.get("java.util", "Map"), ParameterizedTypeName.get(
					ClassName.get("java.lang", "Class"),
					WildcardTypeName.subtypeOf(TypeName.get(this.dispatcherInterface))),
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
					.addStatement("$T $L = $N.get($T.class)", varName, this.listenerInterface, listenersParam, listener.parent)
					.addStatement("if($L.isActive()) (($T) $L).$L($N)", varName, listener.parent,
						listener.method.getSimpleName().toString(), eventParam);
			}

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
				JavaFileObject injectorFile = processingEnv.getFiler().createSourceFile(resultingClassName);
				PrintWriter out = new PrintWriter(injectorFile.openWriter());
				javaFile.writeTo(out);
				out.close();
			} catch(IOException e) {
				throw new RuntimeException(e);
			}
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

	private static class ListenerContainer {
		public final ExecutableElement method;
		public final TypeMirror parent;
		public final Listen annotation;

		public ListenerContainer(ExecutableElement method) {
			this.method = method;
			this.parent = method.getEnclosingElement().asType();
			this.annotation = method.getAnnotation(Listen.class);
		}
	}
}
