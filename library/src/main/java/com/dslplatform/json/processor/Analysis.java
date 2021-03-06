package com.dslplatform.json.processor;

import com.dslplatform.json.*;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.*;

public class Analysis {

	private final AnnotationUsage annotationUsage;
	private final LogLevel logLevel;
	private final UnknownTypes unknownTypes;
	private final boolean mustHaveEmptyCtor;
	private final boolean includeFields;
	private final boolean includeBeanMethods;
	private final boolean includeExactMethods;

	private final Elements elements;
	private final Types types;
	private final Messager messager;

	public final TypeElement compiledJsonElement;
	public final DeclaredType compiledJsonType;
	public final TypeElement attributeElement;
	public final DeclaredType attributeType;
	public final TypeElement converterElement;
	public final DeclaredType converterType;

	private final Set<String> supportedTypes;
	private final ContainerSupport containerSupport;
	private final Set<String> alternativeIgnore;
	private final Map<String, List<AnnotationMapping<Boolean>>> alternativeNonNullable;
	private final Map<String, String> alternativeAlias;
	private final Map<String, List<AnnotationMapping<Boolean>>> alternativeMandatory;
	private final Set<String> alternativeCtors;
	private final Map<String, String> alternativeIndex;

	public static class AnnotationMapping<T> {
		public final String name;
		public final T value;
		public AnnotationMapping(String name, T value) {
			this.name = name;
			this.value = value;
		}
	}

	private final Map<String, StructInfo> structs = new LinkedHashMap<String, StructInfo>();

	private boolean hasError;

	public boolean hasError() {
		return hasError;
	}

	public Analysis(ProcessingEnvironment processingEnv, AnnotationUsage annotationUsage, LogLevel logLevel, Set<String> supportedTypes, ContainerSupport containerSupport) {
		this(processingEnv, annotationUsage, logLevel, supportedTypes, containerSupport, null, null, null, null, null, null, UnknownTypes.ERROR, false, true, true, true);
	}

	public Analysis(
			ProcessingEnvironment processingEnv,
			AnnotationUsage annotationUsage,
			LogLevel logLevel,
			Set<String> supportedTypes,
			ContainerSupport containerSupport,
			Set<String> alternativeIgnore,
			Map<String, List<AnnotationMapping<Boolean>>> alternativeNonNullable,
			Map<String, String> alternativeAlias,
			Map<String, List<AnnotationMapping<Boolean>>> alternativeMandatory,
			Set<String> alternativeCtors,
			Map<String, String> alternativeIndex,
			UnknownTypes unknownTypes,
			boolean mustHaveEmptyCtor,
			boolean includeFields,
			boolean includeBeanMethods,
			boolean includeExactMethods) {
		this.annotationUsage = annotationUsage;
		this.logLevel = logLevel;
		this.elements = processingEnv.getElementUtils();
		this.types = processingEnv.getTypeUtils();
		this.messager = processingEnv.getMessager();
		this.compiledJsonElement = elements.getTypeElement(CompiledJson.class.getName());
		this.compiledJsonType = types.getDeclaredType(compiledJsonElement);
		this.attributeElement = elements.getTypeElement(JsonAttribute.class.getName());
		this.attributeType = types.getDeclaredType(attributeElement);
		this.converterElement = elements.getTypeElement(JsonConverter.class.getName());
		this.converterType = types.getDeclaredType(converterElement);
		this.supportedTypes = supportedTypes;
		this.containerSupport = containerSupport;
		this.alternativeIgnore = alternativeIgnore == null ? new HashSet<String>() : alternativeIgnore;
		this.alternativeNonNullable = alternativeNonNullable == null ? new HashMap<String, List<AnnotationMapping<Boolean>>>() : alternativeNonNullable;
		this.alternativeAlias = alternativeAlias == null ? new HashMap<String, String>() : alternativeAlias;
		this.alternativeMandatory = alternativeMandatory == null ? new HashMap<String, List<AnnotationMapping<Boolean>>>() : alternativeMandatory;
		this.alternativeCtors = alternativeCtors == null ? new HashSet<String>() : alternativeCtors;
		this.alternativeIndex = alternativeIndex == null ? new HashMap<String, String>() : alternativeIndex;
		this.unknownTypes = unknownTypes == null ? UnknownTypes.ERROR : unknownTypes;
		this.mustHaveEmptyCtor = mustHaveEmptyCtor;
		this.includeFields = includeFields;
		this.includeBeanMethods = includeBeanMethods;
		this.includeExactMethods = includeExactMethods;
	}

	public List<String> processConverters(Set<? extends Element> converters) {
		List<String> configurations = new ArrayList<String>();
		for (Element el : converters) {
			findConverters(el);
			if (el instanceof TypeElement) {
				TypeElement te = (TypeElement) el;
				if (!el.getModifiers().contains(Modifier.ABSTRACT)) {
					for (TypeElement it : getTypeHierarchy((TypeElement) el)) {
						if (Configuration.class.getName().equals(it.toString())) {
							if (te.getNestingKind().isNested()) {
								configurations.add(te.getEnclosingElement().asType().toString() + "$" + te.getSimpleName().toString());
							} else {
								configurations.add(te.asType().toString());
							}
							break;
						}
					}
				}
			}
		}
		return configurations;
	}

	public void processAnnotation(DeclaredType currentAnnotationType, Set<? extends Element> targets) {
		Stack<String> path = new Stack<String>();
		for (Element el : targets) {
			Element classElement;
			if (el instanceof TypeElement) classElement = el;
			else classElement = el.getEnclosingElement();
			findStructs(classElement, currentAnnotationType, currentAnnotationType + " requires accessible public constructor", path);
		}
		findRelatedReferences();
		findImplementations(structs.values());
	}

	public Map<String, StructInfo> analyze() {
		for (Map.Entry<String, StructInfo> it : structs.entrySet()) {
			final StructInfo info = it.getValue();
			final String className = it.getKey();
			if (info.type == ObjectType.CLASS && info.constructor == null
					&& info.converter == null && info.matchingConstructors != null) {
				hasError = true;
				if (info.matchingConstructors.size() == 0) {
					messager.printMessage(
							Diagnostic.Kind.ERROR,
							"No matching constructors found for '" + info.element.asType() + "'. Make sure there is at least one matching constructor available.",
							info.element,
							info.annotation);
				} else {
					messager.printMessage(
							Diagnostic.Kind.ERROR,
							"Multiple matching constructors found for '" + info.element.asType() + "'. Use @CompiledJson or alternative annotations to select the appropriate constructor.",
							info.element,
							info.annotation);
				}
			}
			if (unknownTypes != UnknownTypes.ALLOW && !info.unknowns.isEmpty()) {
				for (Map.Entry<String, TypeMirror> kv : info.unknowns.entrySet()) {
					AttributeInfo attr = info.attributes.get(kv.getKey());
					if (attr != null && (attr.converter != null || attr.isJsonObject)) continue;
					Map<String, Boolean> references = analyzeParts(kv.getValue());
					for (Map.Entry<String, Boolean> pair : references.entrySet()) {
						if (!pair.getValue()) {
							hasError = hasError || unknownTypes == UnknownTypes.ERROR;
							Diagnostic.Kind kind = unknownTypes == UnknownTypes.ERROR ? Diagnostic.Kind.ERROR : Diagnostic.Kind.WARNING;
							if (kind == Diagnostic.Kind.ERROR || logLevel.isVisible(LogLevel.INFO)) {
								if (kv.getValue().toString().equals(pair.getKey())) {
									messager.printMessage(
											kind,
											"Property " + kv.getKey() + " is referencing unknown type: '" + kv.getValue()
													+ "'. Register custom converter, mark property as ignored or enable unknown types",
											attr != null ? attr.element : info.element,
											info.annotation);
								} else {
									messager.printMessage(
											kind,
											"Property " + kv.getKey() + " is referencing unknown type: '" + kv.getValue() + "' which has an unknown part: '"
													+ pair.getKey() + "'. Register custom converter, mark property as ignored or enable unknown types",
											attr != null ? attr.element : info.element,
											info.annotation);
								}
							}
						}
					}
				}
			}
			if (unknownTypes != UnknownTypes.ALLOW) {
				for (AttributeInfo attr : info.attributes.values()) {
					if (attr.converter != null || attr.isJsonObject) continue;
					Map<String, Boolean> references = analyzeParts(attr.type);
					for (String r : references.keySet()) {
						StructInfo target = structs.get(r);
						if (target != null && target.type == ObjectType.MIXIN && target.implementations.size() == 0) {
							String what = target.element.getKind() == ElementKind.INTERFACE ? "interface" : "abstract class";
							String one = target.element.getKind() == ElementKind.INTERFACE ? "implementation" : "concrete extension";
							hasError = true;
							messager.printMessage(Diagnostic.Kind.ERROR, "Property " + attr.name +
											" is referencing " + what + " (" + target.element.getQualifiedName() + ") which doesn't have registered " +
											"implementations with @CompiledJson. At least one " + one + " of specified " + what + " must be annotated " +
											"with CompiledJson annotation or allow unknown types during analysis",
									attr.element,
									info.annotation);
						}
					}
				}
			}
			if (unknownTypes != UnknownTypes.ALLOW && info.type == ObjectType.MIXIN && info.implementations.isEmpty()) {
				String what = info.element.getKind() == ElementKind.INTERFACE ? "Interface" : "Abstract class";
				String one = info.element.getKind() == ElementKind.INTERFACE ? "implementation" : "concrete extension";
				hasError = hasError || unknownTypes == UnknownTypes.ERROR;
				Diagnostic.Kind kind = unknownTypes == UnknownTypes.ERROR ? Diagnostic.Kind.ERROR : Diagnostic.Kind.WARNING;
				if (kind == Diagnostic.Kind.ERROR || logLevel.isVisible(LogLevel.INFO)) {
					messager.printMessage(
							kind,
							what + " (" + className + ") is referenced, but it doesn't have registered " +
									"implementations with @CompiledJson. At least one " + one + " of specified " + what + " must be annotated " +
									"with CompiledJson annotation or allow unknown types during analysis",
							info.element,
							info.annotation);
				}
			}
			if (info.type == ObjectType.CLASS && info.converter == null && !info.hasEmptyCtor() && info.constructor != null) {
				for (VariableElement p : info.constructor.getParameters()) {
					boolean found = false;
					String argName = p.getSimpleName().toString();
					for (AttributeInfo attr : info.attributes.values()) {
						if (attr.name.equals(argName)) {
							found = true;
							break;
						}
					}
					if (!found) {
						hasError = true;
						messager.printMessage(
								Diagnostic.Kind.ERROR,
								"Unable to find matching property: '" + argName + "' used in constructor. Either use annotation processor on source code, on bytecode with -parameters flag (to enable parameter names) or manually create an instance via converter",
								info.constructor,
								info.annotation);
					}
				}
			}
			if (info.checkHashCollision()) {
				hasError = true;
				messager.printMessage(
						Diagnostic.Kind.ERROR,
						"Duplicate hash value detected. Unable to create binding for: '" + className + "'. Remove (or reduce) alternativeNames from @JsonAttribute to resolve this issue." + info.pathDescription(),
						info.element,
						info.annotation);
			}
			if (info.deserializeAs != null) {
				StructInfo target = structs.get(info.deserializeAs.asType().toString());
				info.deserializeTarget(target);
				if (target == null) {
					hasError = true;
					messager.printMessage(
							Diagnostic.Kind.ERROR,
							"Unable to find DSL-JSON metadata for: '" + info.deserializeAs.getQualifiedName() + "'. Add @CompiledJson annotation to target type.",
							info.element,
							info.annotation);
				}
			}
			if (info.deserializeAs == null && info.type == ObjectType.MIXIN) {
				Set<String> names = new HashSet<String>();
				for(StructInfo im : info.implementations) {
					String actualName = im.deserializeName.isEmpty() ? im.element.getQualifiedName().toString() : im.deserializeName;
					if (!names.add(actualName)) {
						hasError = true;
						messager.printMessage(
								Diagnostic.Kind.ERROR,
								"Duplicate deserialization name detected: '" + actualName + "' for mixin: " + className,
								info.element,
								info.annotation);
					} else if (actualName.contains("\\") || actualName.contains("\"")) {
						hasError = true;
						messager.printMessage(
								Diagnostic.Kind.ERROR,
								"Invalid deserialization name (with quotes or escape chars) detected: '" + actualName + "' for mixin: " + className,
								info.element,
								info.annotation);
					}
				}
			}
			if (info.type == ObjectType.CLASS && mustHaveEmptyCtor && info.converter == null && !info.hasEmptyCtor()) {
				hasError = true;
				messager.printMessage(
						Diagnostic.Kind.ERROR,
						"'" + className + "' requires public no argument constructor" + info.pathDescription(),
						info.element,
						info.annotation);
			} else if (info.type == ObjectType.CLASS && !mustHaveEmptyCtor && !info.hasEmptyCtor() && info.converter == null && (info.constructor == null || info.constructor.getParameters().size() != info.attributes.size())) {
				hasError = true;
				messager.printMessage(
						Diagnostic.Kind.ERROR,
						"'" + className + "' does not have an empty or matching constructor" + info.pathDescription(),
						info.element,
						info.annotation);
			}
			if (info.formats.contains(CompiledJson.Format.ARRAY)) {
				HashSet<Integer> ids = new HashSet<Integer>();
				for (AttributeInfo attr : info.attributes.values()) {
					if (attr.index == -1 && info.hasEmptyCtor()) {
						hasError = true;
						messager.printMessage(
								Diagnostic.Kind.ERROR,
								"When array format is used all properties must have index order defined. Property " + attr.name + " doesn't have index defined",
								attr.element,
								attr.annotation);
					} else if (attr.index != -1 && !ids.add(attr.index)) {
						hasError = true;
						messager.printMessage(
								Diagnostic.Kind.ERROR,
								"Duplicate index detected on " + attr.name + ". Index values must be distinct to be used in array format",
								attr.element,
								attr.annotation);
					}
				}
			}
			if (info.isMinified) {
				info.prepareMinifiedNames();
			}
			info.sortAttributes();
		}
		return new LinkedHashMap<String, StructInfo>(structs);
	}

	private void findConverters(Element el) {
		AnnotationMirror dslAnn = getAnnotation(el, converterType);
		if (!(el instanceof TypeElement) || dslAnn == null) {
			return;
		}
		TypeElement converter = (TypeElement) el;
		DeclaredType target = null;
		Map<? extends ExecutableElement, ? extends AnnotationValue> values = dslAnn.getElementValues();
		for (ExecutableElement ee : values.keySet()) {
			if (ee.toString().equals("target()")) {
				target = (DeclaredType) values.get(ee).getValue();
				break;
			}
		}
		if (target == null) return;
		validateConverter(converter, target.asElement(), target.toString());
		//TODO: throw an error if multiple non-compatible converters were found!?
		if (!structs.containsKey(target.toString())) {
			String name = "struct" + structs.size();
			TypeElement element = (TypeElement) target.asElement();
			StructInfo info = new StructInfo(converter, converterType, element, name);
			structs.put(target.toString(), info);
		}
	}

	private void validateConverter(TypeElement converter, Element target, String fullName) {
		VariableElement jsonReader = null;
		VariableElement jsonWriter = null;
		for (VariableElement field : ElementFilter.fieldsIn(converter.getEnclosedElements())) {
			if ("JSON_READER".equals(field.getSimpleName().toString())) {
				jsonReader = field;
			} else if ("JSON_WRITER".equals(field.getSimpleName().toString())) {
				jsonWriter = field;
			}
		}
		if (!converter.getModifiers().contains(Modifier.PUBLIC)) {
			hasError = true;
			messager.printMessage(
					Diagnostic.Kind.ERROR,
					"Specified converter: '" + converter.asType() + "' must be public",
					converter,
					getAnnotation(converter, converterType));
		} else if (!target.getModifiers().contains(Modifier.PUBLIC)) {
			hasError = true;
			messager.printMessage(
					Diagnostic.Kind.ERROR,
					"Specified converter target: '" + fullName + "' must be public",
					converter,
					getAnnotation(converter, converterType));
		} else if (converter.getNestingKind().isNested() && !converter.getModifiers().contains(Modifier.STATIC)) {
			hasError = true;
			messager.printMessage(
					Diagnostic.Kind.ERROR,
					"Specified converter: '" + converter.asType() + "' can't be a nested member. Only public static nested classes are supported",
					converter,
					getAnnotation(converter, converterType));
		} else if (converter.getQualifiedName().contentEquals(converter.getSimpleName())
				|| converter.getNestingKind().isNested() && converter.getModifiers().contains(Modifier.STATIC)
				&& converter.getEnclosingElement() instanceof TypeElement
				&& ((TypeElement) converter.getEnclosingElement()).getQualifiedName().contentEquals(converter.getEnclosingElement().getSimpleName())) {
			hasError = true;
			messager.printMessage(
					Diagnostic.Kind.ERROR,
					"Specified converter: '" + converter.getQualifiedName() + "' is defined without a package name and cannot be accessed",
					converter,
					getAnnotation(converter, converterType));
		} else if (jsonReader == null || jsonWriter == null) {
			hasError = true;
			messager.printMessage(
					Diagnostic.Kind.ERROR,
					"Specified converter: '" + converter.getQualifiedName() + "' doesn't have a JSON_READER or JSON_WRITER field. It must have public static JSON_READER/JSON_WRITER fields for conversion.",
					converter,
					getAnnotation(converter, converterType));
		} else if (!jsonReader.getModifiers().contains(Modifier.PUBLIC)
				|| !jsonReader.getModifiers().contains(Modifier.STATIC)
				|| !jsonWriter.getModifiers().contains(Modifier.PUBLIC)
				|| !jsonWriter.getModifiers().contains(Modifier.STATIC)) {
			hasError = true;
			messager.printMessage(
					Diagnostic.Kind.ERROR,
					"Specified converter: '" + converter.getQualifiedName() + "' doesn't have public and static JSON_READER and JSON_WRITER fields. They must be public and static for converter to work properly.",
					converter,
					getAnnotation(converter, converterType));
		} else if (!("com.dslplatform.json.JsonReader.ReadObject<" + fullName + ">").equals(jsonReader.asType().toString())) {
			hasError = true;
			messager.printMessage(
					Diagnostic.Kind.ERROR,
					"Specified converter: '" + converter.getQualifiedName() + "' has invalid type for JSON_READER field. It must be of type: 'com.dslplatform.json.JsonReader.ReadObject<" + target + ">'",
					converter,
					getAnnotation(converter, converterType));
		} else if (!("com.dslplatform.json.JsonWriter.WriteObject<" + fullName + ">").equals(jsonWriter.asType().toString())) {
			hasError = true;
			messager.printMessage(
					Diagnostic.Kind.ERROR,
					"Specified converter: '" + converter.getQualifiedName() + "' has invalid type for JSON_WRITER field. It must be of type: 'com.dslplatform.json.JsonWriter.WriteObject<" + target + ">'",
					converter,
					getAnnotation(converter, converterType));
		}
	}

	public List<TypeElement> getTypeHierarchy(TypeElement element) {
		List<TypeElement> result = new ArrayList<TypeElement>();
		result.add(element);
		for (TypeMirror type : types.directSupertypes(element.asType())) {
			Element current = types.asElement(type);
			if (current instanceof TypeElement) {
				result.add((TypeElement) current);
			}
		}
		return result;
	}

	public void findRelatedReferences() {
		int total;
		do {
			total = structs.size();
			List<StructInfo> items = new ArrayList<StructInfo>(structs.values());
			Stack<String> path = new Stack<String>();
			for (StructInfo info : items) {
				if (info.converter != null) continue;
				path.push(info.element.getSimpleName().toString());
				if (includeBeanMethods) {
					for (Map.Entry<String, AccessElements> p : getBeanProperties(info.element, info.constructor).entrySet()) {
						analyzeAttribute(info, p.getValue().read.getReturnType(), p.getKey(), p.getValue(), "bean property", path);
					}
				}
				if (includeExactMethods) {
					for (Map.Entry<String, AccessElements> p : getExactProperties(info.element, info.constructor).entrySet()) {
						if (!info.attributes.containsKey(p.getKey()) || info.annotation != null) {
							analyzeAttribute(info, p.getValue().read.getReturnType(), p.getKey(), p.getValue(), "exact property", path);
						}
					}
				}
				if (includeFields) {
					for (Map.Entry<String, AccessElements> f : getPublicFields(info.element, mustHaveEmptyCtor, info.constructor).entrySet()) {
						if (!info.attributes.containsKey(f.getKey()) || info.annotation != null) {
							analyzeAttribute(info, f.getValue().field.asType(), f.getKey(), f.getValue(), "field", path);
						}
					}
				}
				path.pop();
			}
		} while (total != structs.size());
	}

	public static String objectName(final String type) {
		return "int".equals(type) ? "java.lang.Integer"
				: "long".equals(type) ? "java.lang.Long"
				: "double".equals(type) ? "java.lang.Double"
				: "float".equals(type) ? "java.lang.Float"
				: "char".equals(type) ? "java.lang.Character"
				: "byte".equals(type) ? "java.lang.Byte"
				: "short".equals(type) ? "java.lang.Short"
				: "boolean".equals(type) ? "java.lang.Boolean"
				: type;
	}

	private void analyzeAttribute(StructInfo info, TypeMirror type, String name, AccessElements access, String target, Stack<String> path) {
		Element element = access.field != null ? access.field : access.read;
		path.push(name);
		if (!info.properties.contains(element) && !hasIgnoredAnnotation(element)) {
			TypeMirror referenceType = access.field != null ? access.field.asType() : access.read.getReturnType();
			Element referenceElement = types.asElement(referenceType);
			TypeMirror converter = findConverter(element);
			String referenceName = referenceType.toString();
			boolean isJsonObject = isJsonObject(referenceElement);
			boolean typeResolved = converter != null || isJsonObject || supportedTypes.contains(referenceName) || structs.containsKey(referenceName);
			boolean hasUnknown = false;
			if (!typeResolved) {
				Map<String, Boolean> references = analyzeParts(referenceType);
				for (Map.Entry<String, Boolean> kv : references.entrySet()) {
					if (!kv.getValue()) {
						hasUnknown = true;
					}
				}
			}
			AnnotationMirror annotation = access.annotation;
			CompiledJson.TypeSignature typeSignature = typeSignatureValue(annotation);
			AttributeInfo attr =
					new AttributeInfo(
							name,
							access.read,
							access.write,
							access.field,
							type,
							annotation,
							hasNonNullable(element, annotation),
							hasMandatoryAnnotation(element, annotation),
							index(element, annotation),
							findNameAlias(element, annotation),
							isFullMatch(element, annotation),
							typeSignature,
							converter,
							isJsonObject);
			String[] alternativeNames = getAlternativeNames(attr.element);
			if (alternativeNames != null) {
				attr.alternativeNames.addAll(Arrays.asList(alternativeNames));
			}
			if (attr.converter != null) {
				TypeElement typeConverter = elements.getTypeElement(attr.converter.toString());
				String javaType = type.toString();
				String objectType = objectName(javaType);
				Element declaredType = objectType.equals(javaType)
						? types.asElement(type)
						: elements.getTypeElement(objectType);
				validateConverter(typeConverter, declaredType, objectType);
			}
			AttributeInfo other = info.attributes.get(attr.id);
			if (other != null
					&& (other.annotation != null && attr.annotation == null
						|| other.annotation == null && attr.annotation == null && other.field == null && attr.field != null)) {
				//if other property has annotation, but this does not, skip over this property
				//if both properties don't have annotation, use the non field one
				path.pop();
				return;
			} else if (other != null
					&& (!other.name.equals(attr.name)
						|| other.id.equals(attr.id) && other.field == null && attr.field == null)) {
				//if properties have different name or both are method based raise an error
				hasError = true;
				messager.printMessage(
						Diagnostic.Kind.ERROR,
						"Duplicate alias detected on " + (attr.field != null ? "field: " : "property: ") + attr.name,
						attr.element,
						info.annotation);
			}
			if (!typeResolved && hasUnknown) {
				info.unknowns.put(attr.id, referenceType);
			}
			info.attributes.put(attr.id, attr);
			info.properties.add(attr.element);
			checkRelatedProperty(type, info.discoveredBy, target, info.element, element, path);
		}
		path.pop();
	}

	private void checkRelatedProperty(TypeMirror returnType, DeclaredType discoveredBy, String access, Element inside, Element property, Stack<String> path) {
		TypeMirror converter = findConverter(property);
		if (converter != null) return;
		String typeName = returnType.toString();
		if (supportedTypes.contains(typeName)) return;
		TypeElement el = elements.getTypeElement(typeName);
		if (el != null) {
			findStructs(el, discoveredBy, el + " is referenced as " + access + " from '" + inside.asType() + "' through CompiledJson annotation.", path);
			return;
		}
		if (returnType instanceof ArrayType) {
			ArrayType at = (ArrayType) returnType;
			el = elements.getTypeElement(at.getComponentType().toString());
			if (el != null) {
				findStructs(el, discoveredBy,el + " is referenced as array " + access + " from '" + inside.asType() + "' through CompiledJson annotation.", path);
				return;
			}
		}
		int genInd = typeName.indexOf('<');
		if (genInd == -1) return;
		String subtype = typeName.substring(genInd + 1, typeName.lastIndexOf('>'));
		if (supportedTypes.contains(subtype)) return;
		Set<String> parts = new LinkedHashSet<String>();
		extractTypes(subtype, parts);
		for (String st : parts) {
			if (!structs.containsKey(st) && !supportedTypes.contains(st)) {
				el = elements.getTypeElement(st);
				if (el != null) {
					if (!el.getTypeParameters().isEmpty()) {
						if (containerSupport.isSupported(st)) continue;
					}
					findStructs(el, discoveredBy, el + " is referenced as collection " + access + " from '" + inside.asType() + "' through CompiledJson annotation.", path);
				}
			}
		}
	}

	private void findStructs(Element el, DeclaredType discoveredBy, String errorMessge, Stack<String> path) {
		if (!(el instanceof TypeElement)) return;
		String typeName = el.asType().toString();
		if (structs.containsKey(typeName) || supportedTypes.contains(typeName)) return;
		final TypeElement element = (TypeElement) el;
		boolean isMixin = element.getKind() == ElementKind.INTERFACE
				|| element.getKind() == ElementKind.CLASS && element.getModifiers().contains(Modifier.ABSTRACT);
		boolean isJsonObject = isJsonObject(element);
		final AnnotationMirror annotation = scanClassForAnnotation(element, discoveredBy);
		if (!element.getModifiers().contains(Modifier.PUBLIC)) {
			hasError = true;
			messager.printMessage(
					Diagnostic.Kind.ERROR,
					errorMessge + ", therefore '" + element.asType() + "' must be public ",
					element,
					annotation);
		} else if (element.getNestingKind().isNested() && !element.getModifiers().contains(Modifier.STATIC)) {
			hasError = true;
			messager.printMessage(
					Diagnostic.Kind.ERROR,
					errorMessge + ", therefore '" + element.asType() + "' can't be a nested member. Only static nested classes are supported.",
					element,
					annotation);
		} else if (element.getQualifiedName().contentEquals(element.getSimpleName())
				|| element.getNestingKind().isNested() && element.getModifiers().contains(Modifier.STATIC)
				&& element.getEnclosingElement() instanceof TypeElement
				&& ((TypeElement) element.getEnclosingElement()).getQualifiedName().contentEquals(element.getEnclosingElement().getSimpleName())) {
			hasError = true;
			messager.printMessage(
					Diagnostic.Kind.ERROR,
					errorMessge + ", but class '" + element.getQualifiedName() + "' is defined without a package name and cannot be accessed.",
					element,
					annotation);
		} else {
			ObjectType type = isMixin ? ObjectType.MIXIN : element.getKind() == ElementKind.ENUM ? ObjectType.ENUM : ObjectType.CLASS;
			CompiledJson.Behavior onUnknown = CompiledJson.Behavior.DEFAULT;
			CompiledJson.TypeSignature typeSignature = CompiledJson.TypeSignature.DEFAULT;
			TypeElement deserializeAs = null;
			if (!isJsonObject) {
				if (annotation != null) {
					onUnknown = onUnknownValue(annotation);
					typeSignature = typeSignatureValue(annotation);
					deserializeAs = deserializeAs(annotation);
					if (deserializeAs != null) {
						String error = validateDeserializeAs(element, deserializeAs);
						if (error != null) {
							hasError = true;
							messager.printMessage(
									Diagnostic.Kind.ERROR,
									errorMessge + ", but specified deserializeAs target: '" + deserializeAs.getQualifiedName() + "' " + error,
									element,
									annotation);
							deserializeAs = null;//reset it so that later lookup don't add another error message
						} else {
							if (deserializeAs.asType().toString().equals(element.asType().toString())) {
								deserializeAs = null;
							} else {
								findStructs(deserializeAs, discoveredBy, errorMessge, path);
							}
						}
					}
				} else if (annotationUsage != AnnotationUsage.IMPLICIT) {
					if (annotationUsage == AnnotationUsage.EXPLICIT) {
						hasError = true;
						messager.printMessage(
								Diagnostic.Kind.ERROR,
								"Annotation usage is set to explicit, but '" + element.getQualifiedName() + "' is used implicitly through references. " +
										"Either change usage to implicit, use @Ignore on property referencing this type or register custom converter for problematic type. " + errorMessge,
								element);
					} else if (element.getQualifiedName().toString().startsWith("java.")) {
						hasError = true;
						messager.printMessage(
								Diagnostic.Kind.ERROR,
								"Annotation usage is set to non-java, but '" + element.getQualifiedName() + "' is found in java package. " +
										"Either change usage to implicit, use @Ignore on property referencing this type, register custom converter for problematic type or add annotation to this type. " +
										errorMessge,
								element);
					}
				}
			}
			CompiledJson.Format[] formats = getFormats(annotation);
			if ((new HashSet<CompiledJson.Format>(Arrays.asList(formats))).size() != formats.length) {
				hasError = true;
				messager.printMessage(
						Diagnostic.Kind.ERROR,
						"Duplicate format detected on '" + element.getQualifiedName() + "'.",
						element,
						annotation);
			}
			String name = "struct" + structs.size();
			StructInfo info =
					new StructInfo(
							element,
							discoveredBy,
							name,
							type,
							isJsonObject,
							findMatchingConstructors(element),
							findAnnotatedConstructor(element, discoveredBy),
							annotation,
							onUnknown,
							typeSignature,
							deserializeAs,
							deserializeName(annotation),
							isMinified(annotation),
							formats);
			info.path.addAll(path);
			if (type == ObjectType.ENUM) {
				info.constants.addAll(getEnumConstants(info.element));
			}
			structs.put(typeName, info);
		}
	}

	private String validateDeserializeAs(TypeElement source, TypeElement target) {
		if (!target.getModifiers().contains(Modifier.PUBLIC)) {
			return "must be public";
		} else if (target.getNestingKind().isNested() && !target.getModifiers().contains(Modifier.STATIC)) {
			return "can't be a nested member. Only public static nested classes are supported";
		} else if (target.getQualifiedName().contentEquals(target.getSimpleName())
				|| target.getNestingKind().isNested() && target.getModifiers().contains(Modifier.STATIC)
				&& target.getEnclosingElement() instanceof TypeElement
				&& ((TypeElement) target.getEnclosingElement()).getQualifiedName().contentEquals(target.getEnclosingElement().getSimpleName())) {
			//TODO: create converters in root package
			return "is defined without a package name and cannot be accessed";
		} else if (target.getKind() == ElementKind.INTERFACE || target.getModifiers().contains(Modifier.ABSTRACT)) {
			return "must be a concrete type";
		} else if (!source.asType().toString().equals(target.asType().toString()) && source.getKind() != ElementKind.INTERFACE && !source.getModifiers().contains(Modifier.ABSTRACT)) {
			return "can only be specified for interfaces and abstract classes. '" + source + "' is neither interface nor abstract class";
		} else if (!types.isAssignable(target.asType(), source.asType())) {
			return "is not assignable to '" + source.getQualifiedName() + "'";
		} else {
			return null;
		}
	}

	public List<ExecutableElement> findMatchingConstructors(Element element) {
		if (element.getKind() == ElementKind.INTERFACE
				|| element.getKind() == ElementKind.ENUM
				|| element.getKind() == ElementKind.CLASS && element.getModifiers().contains(Modifier.ABSTRACT)) {
			return null;
		}
		List<ExecutableElement> matchingCtors = new ArrayList<ExecutableElement>();
		for (ExecutableElement constructor : ElementFilter.constructorsIn(element.getEnclosedElements())) {
			if (constructor.getModifiers().contains(Modifier.PUBLIC)) {
				matchingCtors.add(constructor);
			}
		}
		return matchingCtors;
	}

	public ExecutableElement findAnnotatedConstructor(Element element, DeclaredType discoveredBy) {
		if (element.getKind() == ElementKind.INTERFACE
				|| element.getKind() == ElementKind.ENUM
				|| element.getKind() == ElementKind.CLASS && element.getModifiers().contains(Modifier.ABSTRACT)) {
			return null;
		}
		for (ExecutableElement constructor : ElementFilter.constructorsIn(element.getEnclosedElements())) {
			AnnotationMirror discAnn = getAnnotation(constructor, discoveredBy);
			if (discAnn != null) {
				if (!constructor.getModifiers().contains(Modifier.PUBLIC)) {
					hasError = true;
					messager.printMessage(
							Diagnostic.Kind.ERROR,
							"Constructor in '" + element.asType() + "' is annotated with " + discoveredBy + ", but it's not public.",
							constructor,
							discAnn);
				}
				return constructor;
			}
			for (AnnotationMirror ann : constructor.getAnnotationMirrors()) {
				if (alternativeCtors.contains(ann.getAnnotationType().toString())) {
					if (!constructor.getModifiers().contains(Modifier.PUBLIC)) {
						hasError = true;
						messager.printMessage(
								Diagnostic.Kind.ERROR,
								"Constructor in '" + element.asType() + "' is annotated with " + ann.getAnnotationType() + ", but it's not public.",
								constructor,
								ann);
					}
					return constructor;
				}
			}
		}
		return null;
	}

	private Map<String, Boolean> analyzeParts(TypeMirror target) {
		String typeName = target.toString();
		if (structs.containsKey(typeName)) {
			return Collections.singletonMap(typeName, true);
		} else if (supportedTypes.contains(typeName)) {
			return Collections.singletonMap(typeName, true);
		}
		if (target instanceof ArrayType) {
			ArrayType at = (ArrayType) target;
			return analyzeParts(at.getComponentType());
		}
		int genInd = typeName.indexOf('<');
		if (genInd == -1) return Collections.singletonMap(typeName, false);
		Map<String, Boolean> found = new LinkedHashMap<String, Boolean>();
		String rawClass = typeName.substring(0, genInd);
		TypeElement raw = elements.getTypeElement(rawClass);
		if (raw != null) {
			if (supportedTypes.contains(rawClass)) {
				found.put(rawClass, true);
			} else {
				found.put(rawClass, containerSupport.isSupported(rawClass));
			}
		}
		String subtype = typeName.substring(genInd + 1, typeName.lastIndexOf('>'));
		if (structs.containsKey(subtype) || supportedTypes.contains(subtype)) {
			found.put(subtype, true);
			return found;
		}
		Set<String> parts = new LinkedHashSet<String>();
		extractTypes(subtype, parts);
		for (String st : parts) {
			if (structs.containsKey(st) || supportedTypes.contains(st)) {
				found.put(st, true);
			} else {
				TypeElement el = elements.getTypeElement(st);
				if (el == null || !el.getTypeParameters().isEmpty()) {
					found.put(st, containerSupport.isSupported(st));
				} else if (isJsonObject(el)) {
					found.put(st, true);
				} else {
					found.putAll(analyzeParts(el.asType()));
				}
			}
		}
		return found;
	}

	private void extractTypes(String signature, Set<String> parts) {
		if (signature.isEmpty()) return;
		if (supportedTypes.contains(signature) || structs.containsKey(signature)) {
			parts.add(signature);
			return;
		}
		int nextComma = signature.indexOf(',');
		int nextGen = signature.indexOf('<');
		if (nextComma == -1 && nextGen == -1) {
			parts.add(signature);
		} else if (nextComma != -1 && (nextGen == -1 || nextGen > nextComma)) {
			String first = signature.substring(0, nextComma);
			String second = signature.substring(nextComma + 1, signature.length());
			parts.add(first);
			extractTypes(second, parts);
		} else {
			String first = signature.substring(0, nextGen);
			String second = signature.substring(nextGen + 1, signature.length() - 1);
			parts.add(first);
			extractTypes(second, parts);
		}
	}

	public static List<String> getEnumConstants(TypeElement element) {
		List<String> result = new ArrayList<String>();
		for (VariableElement field : ElementFilter.fieldsIn(element.getEnclosedElements())) {
			//Use to string comparison since compiler can create two separate instances which differ
			if (field.asType().toString().equals(element.asType().toString())) {
				result.add(field.getSimpleName().toString());
			}
		}
		return result;
	}

	public boolean isJsonObject(Element el) {
		if (!(el instanceof TypeElement)) return false;
		TypeElement element = (TypeElement)el;
		for (TypeMirror type : element.getInterfaces()) {
			if (JsonObject.class.getName().equals(type.toString())) {
				for (VariableElement field : ElementFilter.fieldsIn(element.getEnclosedElements())) {
					if ("JSON_READER".equals(field.getSimpleName().toString())) {
						if (!field.getModifiers().contains(Modifier.PUBLIC)
								|| !field.getModifiers().contains(Modifier.STATIC)) {
							hasError = true;
							messager.printMessage(
									Diagnostic.Kind.ERROR,
									"'" + element.getQualifiedName() + "' is 'com.dslplatform.json.JsonObject', but it's JSON_READER field is not public and static. " +
											"It can't be used for serialization/deserialization this way. " +
											"You probably want to change JSON_READER field so it's public and static.",
									element);
						}
						String correctType = "com.dslplatform.json.JsonReader.ReadJsonObject<" + element.getQualifiedName() + ">";
						if (!(correctType.equals(field.asType().toString()))) {
							hasError = true;
							messager.printMessage(
									Diagnostic.Kind.ERROR,
									"'" + element.getQualifiedName() + "' is 'com.dslplatform.json.JsonObject', but it's JSON_READER field is not of correct type. " +
											"It can't be used for serialization/deserialization this way. " +
											"You probably want to change JSON_READER field to: '" + correctType + "'",
									element);
						}
						return true;
					}
				}
				messager.printMessage(
						Diagnostic.Kind.ERROR,
						"'" + element.getQualifiedName() + "' is 'com.dslplatform.json.JsonObject', but it doesn't have JSON_READER field. " +
								"It can't be used for serialization/deserialization this way. " +
								"You probably want to add public static JSON_READER field.",
						element);
				return true;
			}
		}
		return false;
	}

	public static class AccessElements {
		public final ExecutableElement read;
		public final ExecutableElement write;
		public final VariableElement field;
		public final VariableElement ctor;
		public final AnnotationMirror annotation;

		private AccessElements(ExecutableElement read, ExecutableElement write, VariableElement ctor, VariableElement field, AnnotationMirror annotation) {
			this.read = read;
			this.write = write;
			this.field = field;
			this.ctor = ctor;
			this.annotation = annotation;
		}

		public static AccessElements readWrite(ExecutableElement read, ExecutableElement write, AnnotationMirror annotation) {
			return new AccessElements(read, write, null, null, annotation);
		}

		public static AccessElements field(VariableElement field, VariableElement ctor, AnnotationMirror annotation) {
			return new AccessElements(null, null, ctor, field, annotation);
		}

		public static AccessElements readOnly(ExecutableElement read, VariableElement ctor, AnnotationMirror annotation) {
			return new AccessElements(read, null, ctor, null, annotation);
		}
	}

	private Map<String, VariableElement> getCtorArguments(ExecutableElement ctor) {
		if (ctor == null) return Collections.emptyMap();
		Map<String, VariableElement> arguments = new HashMap<String, VariableElement>();
		for (VariableElement p : ctor.getParameters()) {
			arguments.put(p.getSimpleName().toString(), p);
		}
		return arguments;
	}

	public Map<String, AccessElements> getBeanProperties(TypeElement element, ExecutableElement ctor) {
		Map<String, ExecutableElement> setters = new HashMap<String, ExecutableElement>();
		Map<String, ExecutableElement> getters = new HashMap<String, ExecutableElement>();
		Map<String, VariableElement> arguments = getCtorArguments(ctor);
		for (TypeElement inheritance : getTypeHierarchy(element)) {
			boolean isPublicInterface = inheritance.getKind() == ElementKind.INTERFACE
					&& inheritance.getModifiers().contains(Modifier.PUBLIC);
			for (ExecutableElement method : ElementFilter.methodsIn(inheritance.getEnclosedElements())) {
				String name = method.getSimpleName().toString();
				boolean isAccessible = isPublicInterface && !method.getModifiers().contains(Modifier.PRIVATE)
						|| method.getModifiers().contains(Modifier.PUBLIC)
						&& !method.getModifiers().contains(Modifier.STATIC)
						&& !method.getModifiers().contains(Modifier.NATIVE)
						&& !method.getModifiers().contains(Modifier.TRANSIENT)
						&& !method.getModifiers().contains(Modifier.ABSTRACT);
				if (name.length() < 4 || !isAccessible) {
					continue;
				}
				String property = name.substring(3).toUpperCase().equals(name.substring(3)) && name.length() > 4
						? name.substring(3)
						: name.substring(3, 4).toLowerCase() + name.substring(4);
				if (name.startsWith("get")
						&& method.getParameters().size() == 0
						&& method.getReturnType() != null) {
					if (!getters.containsKey(property)) {
						getters.put(property, method);
					}
				} else if (name.startsWith("set")
						&& method.getParameters().size() == 1) {
					setters.put(property, method);
				}
			}
		}
		Map<String, AccessElements> result = new HashMap<String, AccessElements>();
		for (Map.Entry<String, ExecutableElement> kv : getters.entrySet()) {
			ExecutableElement setter = setters.get(kv.getKey());
			VariableElement setterArgument = setter == null ? null : setter.getParameters().get(0);
			VariableElement ctorArg = arguments.get(kv.getKey());
			String returnType = kv.getValue().getReturnType().toString();
			AnnotationMirror annotation = annotation(kv.getValue(), setter, null, ctorArg);
			if (setterArgument != null && setterArgument.asType().toString().equals(returnType)) {
				result.put(kv.getKey(), AccessElements.readWrite(kv.getValue(), setter, annotation));
			} else if (setterArgument != null && (setterArgument.asType() + "<").startsWith(returnType)) {
				result.put(kv.getKey(), AccessElements.readWrite(kv.getValue(), setter, annotation));
			} else if (!mustHaveEmptyCtor && ctorArg != null && ctorArg.asType().toString().equals(returnType)) {
				result.put(kv.getKey(), AccessElements.readOnly(kv.getValue(), ctorArg, annotation));
			}
		}
		return result;
	}

	public Map<String, AccessElements> getExactProperties(TypeElement element, ExecutableElement ctor) {
		Map<String, ExecutableElement> setters = new HashMap<String, ExecutableElement>();
		Map<String, ExecutableElement> getters = new HashMap<String, ExecutableElement>();
		Map<String, VariableElement> arguments = getCtorArguments(ctor);
		for (TypeElement inheritance : getTypeHierarchy(element)) {
			boolean isPublicInterface = inheritance.getKind() == ElementKind.INTERFACE
					&& inheritance.getModifiers().contains(Modifier.PUBLIC);
			for (ExecutableElement method : ElementFilter.methodsIn(inheritance.getEnclosedElements())) {
				String name = method.getSimpleName().toString();
				boolean isAccessible = isPublicInterface && !method.getModifiers().contains(Modifier.PRIVATE)
						|| method.getModifiers().contains(Modifier.PUBLIC)
						&& !method.getModifiers().contains(Modifier.STATIC)
						&& !method.getModifiers().contains(Modifier.NATIVE)
						&& !method.getModifiers().contains(Modifier.TRANSIENT)
						&& !method.getModifiers().contains(Modifier.ABSTRACT);
				if (name.startsWith("get") || name.startsWith("set") || !isAccessible) {
					continue;
				}
				if (method.getParameters().size() == 0 && method.getReturnType() != null) {
					if (!getters.containsKey(name)) {
						getters.put(name, method);
					}
				} else if (method.getParameters().size() == 1) {
					setters.put(name, method);
				}
			}
		}
		Map<String, AccessElements> result = new HashMap<String, AccessElements>();
		for (Map.Entry<String, ExecutableElement> kv : getters.entrySet()) {
			ExecutableElement setter = setters.get(kv.getKey());
			VariableElement setterArgument = setter == null ? null : setter.getParameters().get(0);
			VariableElement ctorArg = arguments.get(kv.getKey());
			String returnType = kv.getValue().getReturnType().toString();
			AnnotationMirror annotation = annotation(kv.getValue(), setter, null, ctorArg);
			if (setterArgument != null && setterArgument.asType().toString().equals(returnType)) {
				result.put(kv.getKey(), AccessElements.readWrite(kv.getValue(), setter, annotation));
			} else if (setterArgument != null && (setterArgument.asType() + "<").startsWith(returnType)) {
				result.put(kv.getKey(), AccessElements.readWrite(kv.getValue(), setter, annotation));
			} else if (!mustHaveEmptyCtor && ctorArg != null && ctorArg.asType().toString().equals(returnType)) {
				result.put(kv.getKey(), AccessElements.readOnly(kv.getValue(), ctorArg, annotation));
			}
		}
		return result;
	}

	public Map<String, AccessElements> getPublicFields(TypeElement element, boolean mustHaveEmptyCtor, ExecutableElement ctor) {
		Map<String, AccessElements> result = new HashMap<String, AccessElements>();
		Map<String, VariableElement> arguments = getCtorArguments(ctor);
		for (TypeElement inheritance : getTypeHierarchy(element)) {
			for (VariableElement field : ElementFilter.fieldsIn(inheritance.getEnclosedElements())) {
				String name = field.getSimpleName().toString();
				boolean isFinal = field.getModifiers().contains(Modifier.FINAL);
				boolean isAccessible = field.getModifiers().contains(Modifier.PUBLIC)
						&& (!isFinal || !mustHaveEmptyCtor)
						&& !field.getModifiers().contains(Modifier.NATIVE)
						&& !field.getModifiers().contains(Modifier.TRANSIENT)
						&& !field.getModifiers().contains(Modifier.STATIC);
				if (!isAccessible) {
					continue;
				}
				VariableElement ctorArg = arguments.get(name);
				AnnotationMirror annotation = annotation(null, null, field, ctorArg);
				result.put(name, AccessElements.field(field, ctorArg, annotation));
			}
		}
		return result;
	}

	public void findImplementations(Collection<StructInfo> structs) {
		for (StructInfo current : structs) {
			if (current.type == ObjectType.MIXIN) {
				String iface = current.element.asType().toString();
				for (StructInfo info : structs) {
					if (info.type == ObjectType.CLASS) {
						for (TypeMirror type : types.directSupertypes(info.element.asType())) {
							if (type.toString().equals(iface)) {
								current.implementations.add(info);
								break;
							}
						}
					}
				}
			}
		}
	}

	public String[] getAlternativeNames(Element property) {
		AnnotationMirror dslAnn = getAnnotation(property, attributeType);
		if (dslAnn == null) return null;
		Map<? extends ExecutableElement, ? extends AnnotationValue> values = dslAnn.getElementValues();
		for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> ee : values.entrySet()) {
			if (ee.getKey().toString().equals("alternativeNames()")) {
				@SuppressWarnings("unchecked")
				List<AnnotationValue> val = (List) ee.getValue().getValue();
				if (val == null) return null;
				String[] names = new String[val.size()];
				for (int i = 0; i < val.size(); i++) {
					names[i] = val.get(i).getValue().toString();
				}
				return names;
			}
		}
		return null;
	}

	public boolean isFullMatch(Element property, AnnotationMirror dslAnn) {
		if (dslAnn == null) return false;
		Map<? extends ExecutableElement, ? extends AnnotationValue> values = dslAnn.getElementValues();
		for (ExecutableElement ee : values.keySet()) {
			if (ee.toString().equals("hashMatch()")) {
				Object val = values.get(ee).getValue();
				return val != null && !((Boolean) val);
			}
		}
		return false;
	}

	public int index(Element property, AnnotationMirror dslAnn) {
		if (dslAnn != null) {
			Map<? extends ExecutableElement, ? extends AnnotationValue> values = dslAnn.getElementValues();
			for (ExecutableElement ee : values.keySet()) {
				if (ee.toString().equals("index()")) {
					Object val = values.get(ee).getValue();
					if (val == null) return -1;
					return (Integer) val;
				}
			}
		}
		for (AnnotationMirror ann : property.getAnnotationMirrors()) {
			Integer index = matchCustomInteger(ann, alternativeIndex);
			if (index != null && index != -1) return index;
		}
		return -1;
	}

	private AnnotationMirror annotation(ExecutableElement read, ExecutableElement write, VariableElement field, VariableElement ctor) {
		AnnotationMirror dslAnn = read == null ? null : getAnnotation(read, attributeType);
		if (dslAnn != null) return dslAnn;
		dslAnn = write == null ? null : getAnnotation(write, attributeType);
		if (dslAnn != null) return dslAnn;
		dslAnn = field == null ? null : getAnnotation(field, attributeType);
		if (dslAnn != null) return dslAnn;
		return ctor == null ? null : getAnnotation(ctor, attributeType);
	}

	public boolean hasIgnoredAnnotation(Element property) {
		AnnotationMirror dslAnn = getAnnotation(property, attributeType);
		if (dslAnn != null) {
			return booleanAnnotationValue(dslAnn, "ignore()", false);
		}
		for (AnnotationMirror ann : property.getAnnotationMirrors()) {
			if (alternativeIgnore.contains(ann.getAnnotationType().toString())) {
				return true;
			}
		}
		return false;
	}

	public AnnotationMirror scanClassForAnnotation(TypeElement element, DeclaredType annotationType) {
		AnnotationMirror target = getAnnotation(element, annotationType);
		if (target != null) return target;
		for (ExecutableElement constructor : ElementFilter.constructorsIn(element.getEnclosedElements())) {
			AnnotationMirror discAnn = getAnnotation(constructor, annotationType);
			if (discAnn != null) return discAnn;
		}
		return null;
	}

	public AnnotationMirror getAnnotation(Element element, DeclaredType annotationType) {
		for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
			if (types.isSameType(mirror.getAnnotationType(), annotationType)) {
				return mirror;
			}
		}
		return null;
	}

	public boolean hasNonNullable(Element property, AnnotationMirror dslAnn) {
		if (dslAnn != null) {
			Map<? extends ExecutableElement, ? extends AnnotationValue> values = dslAnn.getElementValues();
			for (ExecutableElement ee : values.keySet()) {
				if (ee.toString().equals("nullable()")) {
					Object val = values.get(ee).getValue();
					return val != null && !((Boolean) val);
				}
			}
			return false;
		}
		for (AnnotationMirror ann : property.getAnnotationMirrors()) {
			Boolean match = matchCustomBoolean(ann, alternativeNonNullable);
			if (match != null) return match;
		}
		return false;
	}

	public static TypeElement deserializeAs(AnnotationMirror annotation) {
		Map<? extends ExecutableElement, ? extends AnnotationValue> values = annotation.getElementValues();
		for (ExecutableElement ee : values.keySet()) {
			if (ee.toString().equals("deserializeAs()")) {
				DeclaredType target = (DeclaredType) values.get(ee).getValue();
				return (TypeElement) target.asElement();
			}
		}
		return null;
	}

	public static String deserializeName(AnnotationMirror annotation) {
		if (annotation == null) return "";
		Map<? extends ExecutableElement, ? extends AnnotationValue> values = annotation.getElementValues();
		for (ExecutableElement ee : values.keySet()) {
			if (ee.toString().equals("deserializeName()")) {
				return values.get(ee).getValue().toString();
			}
		}
		return "";
	}

	public boolean hasMandatoryAnnotation(Element property, AnnotationMirror dslAnn) {
		if (dslAnn != null) {
			return booleanAnnotationValue(dslAnn, "mandatory()", false);
		}
		for (AnnotationMirror ann : property.getAnnotationMirrors()) {
			Boolean match = matchCustomBoolean(ann, alternativeMandatory);
			if (match != null) return match;
		}
		return false;
	}

	public static boolean booleanAnnotationValue(AnnotationMirror ann, String method, boolean defaultValue) {
		Map<? extends ExecutableElement, ? extends AnnotationValue> values = ann.getElementValues();
		for (ExecutableElement ee : values.keySet()) {
			if (ee.toString().equals(method)) {
				Object val = values.get(ee).getValue();
				return val == null ? defaultValue : (Boolean) val;
			}
		}
		return defaultValue;
	}

	public CompiledJson.Behavior onUnknownValue(AnnotationMirror annotation) {
		if (annotation == null) return null;
		Map<? extends ExecutableElement, ? extends AnnotationValue> values = annotation.getElementValues();
		for (ExecutableElement ee : values.keySet()) {
			if (ee.toString().equals("onUnknown()")) {
				Object val = values.get(ee).getValue();
				if (val == null) return null;
				return CompiledJson.Behavior.valueOf(val.toString());
			}
		}
		return null;
	}

	public CompiledJson.TypeSignature typeSignatureValue(AnnotationMirror annotation) {
		if (annotation == null) return null;
		Map<? extends ExecutableElement, ? extends AnnotationValue> values = annotation.getElementValues();
		for (ExecutableElement ee : values.keySet()) {
			if (ee.toString().equals("typeSignature()")) {
				Object val = values.get(ee).getValue();
				if (val == null) return null;
				return CompiledJson.TypeSignature.valueOf(val.toString());
			}
		}
		return null;
	}

	public CompiledJson.Format[] getFormats(AnnotationMirror ann) {
		if (ann == null) return new CompiledJson.Format[]{CompiledJson.Format.OBJECT};
		Map<? extends ExecutableElement, ? extends AnnotationValue> values = ann.getElementValues();
		for (ExecutableElement ee : values.keySet()) {
			if ("formats()".equals(ee.toString())) {
				Object val = values.get(ee).getValue();
				if (val == null) return new CompiledJson.Format[]{CompiledJson.Format.OBJECT};
				List list = (List)val;
				CompiledJson.Format[] result = new CompiledJson.Format[list.size()];
				for (int i = 0; i < result.length; i++) {
					AnnotationValue enumVal = (AnnotationValue)list.get(i);
					result[i] = CompiledJson.Format.valueOf(enumVal.getValue().toString());
				}
				return result;
			}
		}
		return new CompiledJson.Format[]{CompiledJson.Format.OBJECT};
	}

	public boolean isMinified(AnnotationMirror ann) {
		if (ann == null) return false;
		for (ExecutableElement ee : ann.getElementValues().keySet()) {
			if ("minified()".equals(ee.toString())) {
				AnnotationValue minified = ann.getElementValues().get(ee);
				return (Boolean) minified.getValue();
			}
		}
		return false;
	}

	public TypeMirror findConverter(Element property) {
		AnnotationMirror dslAnn = getAnnotation(property, attributeType);
		if (dslAnn == null) return null;
		Map<? extends ExecutableElement, ? extends AnnotationValue> values = dslAnn.getElementValues();
		for (ExecutableElement ee : values.keySet()) {
			if (ee.toString().equals("converter()")) {
				TypeMirror mirror = (TypeMirror) values.get(ee).getValue();
				return mirror != null && mirror.toString().equals(JsonAttribute.class.getName()) ? null : mirror;
			}
		}
		return null;
	}

	public String findNameAlias(Element property, AnnotationMirror dslAnn) {
		if (dslAnn != null) {
			Map<? extends ExecutableElement, ? extends AnnotationValue> values = dslAnn.getElementValues();
			for (ExecutableElement ee : values.keySet()) {
				if (ee.toString().equals("name()")) {
					String val = (String) values.get(ee).getValue();
					if (val != null && val.length() == 0) return null;
					return val;
				}
			}
			return null;
		}
		for (AnnotationMirror ann : property.getAnnotationMirrors()) {
			String name = matchCustomString(ann, alternativeAlias);
			if (name != null && !name.isEmpty()) return name;
		}
		return null;
	}

	private static Boolean matchCustomBoolean(
			AnnotationMirror ann,
			Map<String, List<AnnotationMapping<Boolean>>> alternatives) {
		String name = ann.getAnnotationType().toString();
		if (alternatives.containsKey(name)) {
			List<AnnotationMapping<Boolean>> mappings = alternatives.get(name);
			if (mappings == null || mappings.isEmpty()) return true;
			for (AnnotationMapping<Boolean> m : mappings) {
				Map<? extends ExecutableElement, ? extends AnnotationValue> values = ann.getElementValues();
				for (ExecutableElement ee : values.keySet()) {
					if (ee.toString().equals(m.name)) {
						Object val = values.get(ee).getValue();
						if (val == null && m.value == null) return true;
						return val != null && val == m.value;
					}
				}
			}
		}
		return null;
	}

	private static String matchCustomString(
			AnnotationMirror ann,
			Map<String, String> alternatives) {
		String value = alternatives.get(ann.getAnnotationType().toString());
		if (value != null) {
			Map<? extends ExecutableElement, ? extends AnnotationValue> values = ann.getElementValues();
			for (ExecutableElement ee : values.keySet()) {
				if (ee.toString().equals(value)) {
					AnnotationValue val = values.get(ee);
					if (val == null) return null;
					if (val.getValue() == null) return null;
					return val.getValue().toString();
				}
			}
		}
		return null;
	}

	private static Integer matchCustomInteger(
			AnnotationMirror ann,
			Map<String, String> alternatives) {
		String value = alternatives.get(ann.getAnnotationType().toString());
		if (value != null) {
			Map<? extends ExecutableElement, ? extends AnnotationValue> values = ann.getElementValues();
			for (ExecutableElement ee : values.keySet()) {
				if (ee.toString().equals(value)) {
					AnnotationValue val = values.get(ee);
					if (val == null) return null;
					if (val.getValue() == null) return null;
					return (Integer)val.getValue();
				}
			}
		}
		return null;
	}
}
