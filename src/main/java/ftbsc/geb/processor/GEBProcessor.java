package ftbsc.geb.processor;

import ftbsc.geb.api.annotations.Event;
import ftbsc.geb.api.annotations.Listen;
import ftbsc.geb.api.annotations.ListenerInstance;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import java.lang.annotation.Annotation;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

@SupportedAnnotationTypes({"ftbsc.geb.api.annotations.*"})
public class GEBProcessor extends AbstractProcessor {
	@Override
	public boolean process(Set<? extends TypeElement> set, RoundEnvironment env) {
		boolean claimed = false;
		for(TypeElement ann : set) {
			BiConsumer<GEBProcessor, Element> processMethod;
			if(ann.getQualifiedName().contentEquals(Listen.class.getName()))
				processMethod = GEBProcessor::processListener;
			else if(ann.getQualifiedName().contentEquals(Event.class.getName()))
				processMethod = GEBProcessor::processEvent;
			else continue;

			claimed = true;

			for(Element e : env.getElementsAnnotatedWith(ann))
				processMethod.accept(this, e);
		}
		return claimed;
	}

	private final Map<Element, List<ExecutableElement>> listeners = new HashMap<>();

	private static List<Element> getMembersAnnotatedWith(TypeElement typeElement, Class<? extends Annotation> ann) {
		return typeElement.getEnclosedElements()
			.stream()
			.filter(elem -> elem.getAnnotation(ann) != null)
			.collect(Collectors.toList());
	}

	private void processListener(Element target) {
		ExecutableElement listener = (ExecutableElement) target; //this will never fail
		Listen listenerAnn = target.getAnnotation(Listen.class);

		//ensure the parent is a class
		if(!(target.getEnclosingElement() instanceof TypeElement))
			return; //TODO throw error, means the annotated field was in a method
		TypeElement parent = (TypeElement) target.getEnclosingElement();

		//ensure the parent is instance of IListener
		TypeElement cursor = parent;
		TypeMirror listenerInterface = this.processingEnv.getElementUtils().getTypeElement("ftbsc.geb.api.IListener").asType()
;		while(cursor != null) {
			if(cursor.getInterfaces().contains(listenerInterface))
				break;

			Element superclass = this.processingEnv.getTypeUtils().asElement(cursor.getSuperclass());
			if(superclass instanceof TypeElement)
				cursor = (TypeElement) superclass;
			else return; //TODO throw error, parent doesnt implement the interface
 		}

		List<Element> instanceSources = getMembersAnnotatedWith(parent, ListenerInstance.class);

		if(instanceSources.size() != 1)
			return; //TODO throw error, there should always be only one per class

		Element instanceSource = instanceSources.get(0);
		List<ExecutableElement> listenerList = listeners.computeIfAbsent(instanceSource, k -> new ArrayList<>());
		listenerList.add(listener);
	}

	private void processEvent(Element target) {
		//TODO
	}
}
