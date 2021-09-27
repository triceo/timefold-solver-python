package org.optaplanner.optapy;

import java.io.PrintStream;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.Type;
import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.lookup.PlanningId;
import org.optaplanner.core.api.domain.solution.PlanningEntityCollectionProperty;
import org.optaplanner.core.api.domain.solution.PlanningSolution;
import org.optaplanner.core.api.domain.solution.ProblemFactCollectionProperty;
import org.optaplanner.core.api.function.TriFunction;
import org.optaplanner.core.api.score.stream.Constraint;
import org.optaplanner.core.api.score.stream.ConstraintFactory;
import org.optaplanner.core.api.score.stream.ConstraintProvider;

import io.quarkus.gizmo.AnnotationCreator;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.ClassOutput;
import io.quarkus.gizmo.FieldDescriptor;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;

public class PythonWrapperGenerator {
    /**
     * The Gizmo generated bytecode. Used by
     * gizmoClassLoader when not run in Quarkus
     * in order to create an instance of the Member
     * Accessor
     */
    private static final Map<String, byte[]> classNameToBytecode = new HashMap<>();

    /**
     * A custom classloader that looks for the class in
     * classNameToBytecode
     */
    static ClassLoader gizmoClassLoader = new ClassLoader() {
        // getName() is an abstract method in Java 11 but not in Java 8
        public String getName() {
            return "OptaPlanner Gizmo Python Wrapper ClassLoader";
        }

        @Override
        public Class<?> findClass(String name) throws ClassNotFoundException {
            if (classNameToBytecode.containsKey(name)) {
                // Gizmo generated class
                byte[] byteCode = classNameToBytecode.get(name);
                return defineClass(name, byteCode, 0, byteCode.length);
            } else {
                // Not a Gizmo generated class; load from parent class loader
                return PythonWrapperGenerator.class.getClassLoader().loadClass(name);
            }
        }
    };

    // These functions are set in Python code
    // Maps a OpaquePythonReference to a unique, numerical id
    static Function<OpaquePythonReference, Number> pythonObjectToId;
    private static Function<OpaquePythonReference, String> pythonObjectToString;

    // Maps a OpaquePythonReference that represents an array to a list of its values
    private static Function<OpaquePythonReference, List<OpaquePythonReference>> pythonArrayIdToIdArray;

    // Reads an attribute on a OpaquePythonReference
    private static BiFunction<OpaquePythonReference, String, Object> pythonObjectIdAndAttributeNameToValue;

    // Sets an attribute on a OpaquePythonReference
    private static TriFunction<OpaquePythonReference, String, Object, Object> pythonObjectIdAndAttributeSetter;

    // These functions are used in Python to set fields to the corresponding Python function
    @SuppressWarnings("unused")
    public static void setPythonObjectToString(Function<OpaquePythonReference, String> pythonObjectToString) {
        PythonWrapperGenerator.pythonObjectToString = pythonObjectToString;
    }

    @SuppressWarnings("unused")
    public static void setPythonObjectToId(Function<OpaquePythonReference, Number> pythonObjectToId) {
        PythonWrapperGenerator.pythonObjectToId = pythonObjectToId;
    }

    @SuppressWarnings("unused")
    public static Object getValueFromPythonObject(OpaquePythonReference objectId, String attributeName) {
        return pythonObjectIdAndAttributeNameToValue.apply(objectId, attributeName);
    }

    @SuppressWarnings("unused")
    public static void setValueOnPythonObject(OpaquePythonReference objectId, String attributeName, Object value) {
        pythonObjectIdAndAttributeSetter.apply(objectId, attributeName, value);
    }

    @SuppressWarnings("unused")
    public static void setPythonArrayIdToIdArray(Function<OpaquePythonReference, List<OpaquePythonReference>> function) {
        pythonArrayIdToIdArray = function;
    }

    @SuppressWarnings("unused")
    public static void setPythonObjectIdAndAttributeNameToValue(BiFunction<OpaquePythonReference, String, Object> function) {
        pythonObjectIdAndAttributeNameToValue = function;
    }

    @SuppressWarnings("unused")
    public static void setPythonObjectIdAndAttributeSetter(
            TriFunction<OpaquePythonReference, String, Object, Object> setter) {
        pythonObjectIdAndAttributeSetter = setter;
    }

    @SuppressWarnings("unused")
    public static String getPythonObjectString(OpaquePythonReference pythonObject) {
        return pythonObjectToString.apply(pythonObject);
    }

    @SuppressWarnings("unused")
    public static OpaquePythonReference getPythonObject(PythonObject pythonObject) {
        return pythonObject.get__optapy_Id();
    }

    // Used in Python to get the array type of a class; used in
    // determining what class a @ProblemFactCollection / @PlanningEntityCollection
    // should be
    @SuppressWarnings("unused")
    public static Class<?> getArrayClass(Class<?> elementClass) {
        return Array.newInstance(elementClass, 0).getClass();
    }

    // Used in Python to exit the JVM.
    // There an issue in Windows where if the JVM
    // is not terminated, a file in use error occurs
    // when removing the temporary directories
    // that contain the dependency jars.
    //
    // TODO: Remove when https://github.com/jpype-project/jpype/issues/1006 is fixed
    @SuppressWarnings("unused")
    public static void shutdownJVM() {
        System.exit(0);
    }

    // Holds the OpaquePythonReference
    static final String pythonBindingFieldName = "__optaplannerPythonValue";

    @SuppressWarnings("unchecked")
    public static <T> T wrap(Class<T> javaClass, OpaquePythonReference object, Map<Number, Object> map) {
        if (object == null) {
            return null;
        }
        // Check to see if we already created the object
        Number id = pythonObjectToId.apply(object);
        if (map.containsKey(id)) {
            return (T) map.get(id);
        }

        try {
            if (javaClass.isArray()) {
                // If the class is an array, we need to extract
                // its elements from the OpaquePythonReference
                List<OpaquePythonReference> itemIds = pythonArrayIdToIdArray.apply(object);
                int length = itemIds.size();
                Object out = Array.newInstance(javaClass.getComponentType(), length);

                // Put the array into the python id to java instance map
                map.put(id, out);

                // Set the elements of the array to the wrapped python items
                for (int i = 0; i < length; i++) {
                    Array.set(out, i, wrap(javaClass.getComponentType(), itemIds.get(i), map));
                }
                return (T) out;
            } else {
                // Create a new instance of the Java Class. Its constructor will put the instance into the map
                return javaClass.getConstructor(OpaquePythonReference.class, Number.class, Map.class).newInstance(object,
                        id, map);
            }
        } catch (IllegalAccessException | NoSuchMethodException | InstantiationException | InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Creates a class that looks like this:
     *
     * {@code 
     * 
     *
     * <pre>
     * class PythonConstraintProvider implements ConstraintProvider {
     *     public static Function<ConstraintFactory, Constraint[]> defineConstraintsImpl;
     *
     *     &#64;Override
     *     public Constraint[] defineConstraints(ConstraintFactory constraintFactory) {
     *         return defineConstraintsImpl.apply(constraintFactory);
     *     }
     * }
     * </pre>
     * 
     * }
     * 
     * @param className The simple name of the generated class
     * @param defineConstraintsImpl The Python function that return the list of constraints
     * @return never null
     */
    @SuppressWarnings("unused")
    public static Class<?> defineConstraintProviderClass(String className,
            Function<ConstraintFactory, Constraint[]> defineConstraintsImpl) {
        className = "org.optaplanner.optapy.generated." + className + ".GeneratedClass";
        if (classNameToBytecode.containsKey(className)) {
            try {
                return gizmoClassLoader.loadClass(className);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "Impossible State: the class (" + className + ") should exists since it was created");
            }
        }
        AtomicReference<byte[]> classBytecodeHolder = new AtomicReference<>();
        ClassOutput classOutput = (path, byteCode) -> {
            classBytecodeHolder.set(byteCode);
        };

        // holds the "defineConstraints" implementation function
        FieldDescriptor valueField;
        try (ClassCreator classCreator = ClassCreator.builder()
                .className(className)
                .interfaces(ConstraintProvider.class)
                .classOutput(classOutput)
                .build()) {
            valueField = classCreator.getFieldCreator("__defineConstraintsImpl", Function.class)
                    .setModifiers(Modifier.STATIC | Modifier.PUBLIC)
                    .getFieldDescriptor();
            MethodCreator methodCreator = classCreator.getMethodCreator(MethodDescriptor.ofMethod(ConstraintProvider.class,
                    "defineConstraints", Constraint[].class, ConstraintFactory.class));

            ResultHandle pythonProxy = methodCreator.readStaticField(valueField);
            ResultHandle constraints = methodCreator.invokeInterfaceMethod(
                    MethodDescriptor.ofMethod(Function.class, "apply", Object.class, Object.class),
                    pythonProxy, methodCreator.getMethodParam(0));
            methodCreator.returnValue(constraints);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        classNameToBytecode.put(className, classBytecodeHolder.get());
        try {
            // Now that the class created, we need to set it static field to the definedConstraints function
            Class<?> out = gizmoClassLoader.loadClass(className);
            out.getField(valueField.getName()).set(null, defineConstraintsImpl);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Impossible State: the class (" + className + ") should exists since it was just created");
        }
    }

    /*
     * The Planning Entity, Problem Fact, and Planning Solution classes look similar, with the only
     * difference being their top-level annotation. They all look like this:
     *
     * (none or @PlanningEntity or @PlanningSolution)
     * public class PojoForPythonObject implements PythonObject {
     * OpaquePythonReference __optaplannerPythonValue;
     * String string$field;
     * AnotherPojoForPythonObject otherObject$field;
     *
     * public PojoForPythonObject(OpaquePythonReference reference, Number id, Map<Number, PythonObject>
     * pythonIdToPythonObjectMap) {
     * this.__optaplannerPythonValue = reference;
     * pythonIdToPythonObjectMap.put(id, this);
     * string$field = PythonWrapperGenerator.getValueFromPythonObject(reference, "string");
     * OpaquePythonReference otherObjectReference = PythonWrapperGenerator.getValueFromPythonObject(reference, "otherObject");
     * otherObject$field = PythonWrapperGenerator.wrap(otherObjectReference, id, pythonIdToPythonObjectMap);
     * }
     *
     * public OpaquePythonReference get__optapy_Id() {
     * return __optaplannerPythonValue;
     * }
     *
     * public String getStringField() {
     * return string$field;
     * }
     *
     * public void setStringField(String val) {
     * PythonWrapperGenerator.setValueOnPythonObject(__optaplannerPythonValue, "string", val);
     * this.string$field = val;
     * }
     * // Repeat for otherObject
     * }
     */
    @SuppressWarnings("unused")
    public static Class<?> definePlanningEntityClass(String className, List<List<Object>> optaplannerMethodAnnotations) {
        className = "org.optaplanner.optapy.generated." + className + ".GeneratedClass";
        if (classNameToBytecode.containsKey(className)) {
            try {
                return gizmoClassLoader.loadClass(className);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "Impossible State: the class (" + className + ") should exists since it was created");
            }
        }
        AtomicReference<byte[]> classBytecodeHolder = new AtomicReference<>();
        ClassOutput classOutput = (path, byteCode) -> {
            classBytecodeHolder.set(byteCode);
        };
        try (ClassCreator classCreator = ClassCreator.builder()
                .className(className)
                .interfaces(PythonObject.class)
                .classOutput(classOutput)
                .build()) {
            classCreator.addAnnotation(PlanningEntity.class);
            FieldDescriptor valueField = classCreator.getFieldCreator(pythonBindingFieldName, OpaquePythonReference.class)
                    .setModifiers(Modifier.PUBLIC).getFieldDescriptor();
            generateWrapperMethods(classCreator, valueField, optaplannerMethodAnnotations);
        }
        classNameToBytecode.put(className, classBytecodeHolder.get());
        try {
            return gizmoClassLoader.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Impossible State: the class (" + className + ") should exists since it was just created");
        }
    }

    @SuppressWarnings("unused")
    public static Class<?> defineProblemFactClass(String className, List<List<Object>> optaplannerMethodAnnotations) {
        className = "org.optaplanner.optapy.generated." + className + ".GeneratedClass";
        if (classNameToBytecode.containsKey(className)) {
            try {
                return gizmoClassLoader.loadClass(className);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "Impossible State: the class (" + className + ") should exists since it was created");
            }
        }
        AtomicReference<byte[]> classBytecodeHolder = new AtomicReference<>();
        ClassOutput classOutput = (path, byteCode) -> {
            classBytecodeHolder.set(byteCode);
        };
        try (ClassCreator classCreator = ClassCreator.builder()
                .className(className)
                .interfaces(PythonObject.class)
                .classOutput(classOutput)
                .build()) {
            FieldDescriptor valueField = classCreator.getFieldCreator(pythonBindingFieldName, OpaquePythonReference.class)
                    .setModifiers(Modifier.PUBLIC).getFieldDescriptor();
            generateWrapperMethods(classCreator, valueField, optaplannerMethodAnnotations);
        }
        classNameToBytecode.put(className, classBytecodeHolder.get());
        try {
            return gizmoClassLoader.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Impossible State: the class (" + className + ") should exists since it was just created");
        }
    }

    @SuppressWarnings("unused")
    public static Class<?> definePlanningSolutionClass(String className, List<List<Object>> optaplannerMethodAnnotations) {
        className = "org.optaplanner.optapy.generated." + className + ".GeneratedClass";
        if (classNameToBytecode.containsKey(className)) {
            try {
                return gizmoClassLoader.loadClass(className);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "Impossible State: the class (" + className + ") should exists since it was created");
            }
        }
        AtomicReference<byte[]> classBytecodeHolder = new AtomicReference<>();
        ClassOutput classOutput = (path, byteCode) -> {
            classBytecodeHolder.set(byteCode);
        };
        try (ClassCreator classCreator = ClassCreator.builder()
                .className(className)
                .interfaces(PythonObject.class)
                .classOutput(classOutput)
                .build()) {
            classCreator.addAnnotation(PlanningSolution.class)
                    .addValue("solutionCloner", Type.getType(PythonPlanningSolutionCloner.class));
            FieldDescriptor valueField = classCreator.getFieldCreator(pythonBindingFieldName, OpaquePythonReference.class)
                    .setModifiers(Modifier.PUBLIC).getFieldDescriptor();
            generateWrapperMethods(classCreator, valueField, optaplannerMethodAnnotations);
        }
        classNameToBytecode.put(className, classBytecodeHolder.get());
        try {
            return gizmoClassLoader.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Impossible State: the class (" + className + ") should exists since it was just created");
        }
    }

    // Used for debugging; prints a result handle
    private static void print(MethodCreator methodCreator, ResultHandle toPrint) {
        ResultHandle out = methodCreator.readStaticField(FieldDescriptor.of(System.class, "out", PrintStream.class));
        methodCreator.invokeVirtualMethod(MethodDescriptor.ofMethod(PrintStream.class, "println", void.class, Object.class),
                out, toPrint);
    }

    // Generate PythonObject interface methods
    private static void generateAsPointer(ClassCreator classCreator, FieldDescriptor valueField) {
        MethodCreator methodCreator = classCreator.getMethodCreator("get__optapy_Id", OpaquePythonReference.class);
        ResultHandle valueResultHandle = methodCreator.readInstanceField(valueField, methodCreator.getThis());
        methodCreator.returnValue(valueResultHandle);
    }

    // Create all methods in the class
    @SuppressWarnings("unchecked")
    private static void generateWrapperMethods(ClassCreator classCreator, FieldDescriptor valueField,
            List<List<Object>> optaplannerMethodAnnotations) {
        generateAsPointer(classCreator, valueField);

        // We only need to create methods/fields for methods with OptaPlanner annotations
        // optaplannerMethodAnnotations: list of tuples (methodName, returnType, annotationList)
        // (Each annotation is represented by a Map)
        List<FieldDescriptor> fieldDescriptorList = new ArrayList<>(optaplannerMethodAnnotations.size());
        List<Class<?>> returnTypeList = new ArrayList<>(optaplannerMethodAnnotations.size());
        for (List<Object> optaplannerMethodAnnotation : optaplannerMethodAnnotations) {
            String methodName = (String) (optaplannerMethodAnnotation.get(0));
            Class<?> returnType = (Class<?>) (optaplannerMethodAnnotation.get(1));
            if (returnType == null) {
                returnType = Object.class;
            }
            List<Map<String, Object>> annotations = (List<Map<String, Object>>) optaplannerMethodAnnotation.get(2);
            fieldDescriptorList
                    .add(generateWrapperMethod(classCreator, valueField, methodName, returnType, annotations, returnTypeList));
        }
        createConstructor(classCreator, valueField, fieldDescriptorList, returnTypeList);
        createToString(classCreator, valueField);
    }

    private static void createToString(ClassCreator classCreator, FieldDescriptor valueField) {
        MethodCreator methodCreator =
                classCreator.getMethodCreator(MethodDescriptor.ofMethod(classCreator.getClassName(), "toString", String.class));
        methodCreator.returnValue(methodCreator.invokeStaticMethod(
                MethodDescriptor.ofMethod(PythonWrapperGenerator.class, "getPythonObjectString", String.class,
                        OpaquePythonReference.class),
                methodCreator.readInstanceField(valueField, methodCreator.getThis())));
    }

    private static void createConstructor(ClassCreator classCreator, FieldDescriptor valueField,
            List<FieldDescriptor> fieldDescriptorList,
            List<Class<?>> returnTypeList) {
        MethodCreator methodCreator = classCreator.getMethodCreator(MethodDescriptor.ofConstructor(classCreator.getClassName(),
                OpaquePythonReference.class, Number.class, Map.class));
        methodCreator.invokeSpecialMethod(MethodDescriptor.ofConstructor(Object.class), methodCreator.getThis());
        methodCreator.invokeInterfaceMethod(
                MethodDescriptor.ofMethod(Map.class, "put", Object.class, Object.class, Object.class),
                methodCreator.getMethodParam(2), methodCreator.getMethodParam(1), methodCreator.getThis());
        ResultHandle value = methodCreator.getMethodParam(0);
        methodCreator.writeInstanceField(valueField, methodCreator.getThis(), value);

        for (int i = 0; i < fieldDescriptorList.size(); i++) {
            FieldDescriptor fieldDescriptor = fieldDescriptorList.get(i);
            Class<?> returnType = returnTypeList.get(i);
            String methodName = fieldDescriptor.getName().substring(0, fieldDescriptor.getName().length() - 6);

            ResultHandle outResultHandle = methodCreator.invokeStaticMethod(
                    MethodDescriptor.ofMethod(PythonWrapperGenerator.class, "getValueFromPythonObject", Object.class,
                            OpaquePythonReference.class, String.class),
                    value, methodCreator.load(methodName));
            if (Comparable.class.isAssignableFrom(returnType)) {
                // It is a number/String, so it already translated to the corresponding Java type
                methodCreator.writeInstanceField(fieldDescriptor, methodCreator.getThis(), outResultHandle);
            } else {
                // We need to wrap it
                methodCreator.writeInstanceField(fieldDescriptor, methodCreator.getThis(),
                        methodCreator.invokeStaticMethod(MethodDescriptor.ofMethod(PythonWrapperGenerator.class,
                                "wrap", Object.class, Class.class, OpaquePythonReference.class, Map.class),
                                methodCreator.loadClass(returnType), outResultHandle, methodCreator.getMethodParam(2)));
            }
        }
        methodCreator.returnValue(methodCreator.getThis());
    }

    private static FieldDescriptor generateWrapperMethod(ClassCreator classCreator, FieldDescriptor valueField,
            String methodName, Class<?> returnType, List<Map<String, Object>> annotations,
            List<Class<?>> returnTypeList) {
        // Python types are not required, so we need to discover them. If the type is unknown, we default to Object,
        // but some annotations need something more specific than Object
        for (Map<String, Object> annotation : annotations) {
            if (PlanningId.class.isAssignableFrom((Class<?>) annotation.get("annotationType"))
                    && !Comparable.class.isAssignableFrom(returnType)) {
                // A PlanningId MUST be comparable
                returnType = Comparable.class;
            } else if ((ProblemFactCollectionProperty.class.isAssignableFrom((Class<?>) annotation.get("annotationType"))
                    || PlanningEntityCollectionProperty.class.isAssignableFrom((Class<?>) annotation.get("annotationType")))
                    && !(Collection.class.isAssignableFrom(returnType) || returnType.isArray())) {
                // A ProblemFactCollection/PlanningEntityCollection MUST be a collection or array
                returnType = Object[].class;
            }
        }
        returnTypeList.add(returnType);
        FieldDescriptor fieldDescriptor = classCreator.getFieldCreator(methodName + "$field", returnType).getFieldDescriptor();
        MethodCreator methodCreator = classCreator.getMethodCreator(methodName, returnType);

        // Create method annotations for each annotation in the list
        for (Map<String, Object> annotation : annotations) {
            // The class representing the annotation is in the annotationType parameter.
            AnnotationCreator annotationCreator = methodCreator.addAnnotation((Class<?>) annotation.get("annotationType"));
            for (Method method : ((Class<?>) annotation.get("annotationType")).getMethods()) {
                if (method.getParameterCount() != 0
                        || !method.getDeclaringClass().equals(annotation.get("annotationType"))) {
                    // skip if the parameter is not from the actual annotation (toString, hashCode, etc.)
                    continue;
                }
                Object annotationValue = annotation.get(method.getName());
                if (annotationValue != null) {
                    annotationCreator.addValue(method.getName(), annotationValue);
                }
            }
        }

        // Getter is simply: return this.field
        methodCreator.returnValue(methodCreator.readInstanceField(fieldDescriptor, methodCreator.getThis()));

        // Assumption: all getters have a setter
        if (methodName.startsWith("get")) {
            String setterMethodName = "set" + methodName.substring(3);
            MethodCreator setterMethodCreator = classCreator.getMethodCreator(setterMethodName, void.class, returnType);

            // Use PythonWrapperGenerator.setValueOnPythonObject(obj, attribute, value)
            // to set the value on the Python Object
            setterMethodCreator.invokeStaticMethod(
                    MethodDescriptor.ofMethod(PythonWrapperGenerator.class, "setValueOnPythonObject", void.class,
                            OpaquePythonReference.class, String.class, Object.class),
                    setterMethodCreator.readInstanceField(valueField, setterMethodCreator.getThis()),
                    setterMethodCreator.load(setterMethodName),
                    setterMethodCreator.getMethodParam(0));
            // Update the field on the Pojo
            setterMethodCreator.writeInstanceField(fieldDescriptor, setterMethodCreator.getThis(),
                    setterMethodCreator.getMethodParam(0));
            setterMethodCreator.returnValue(null);
        }
        return fieldDescriptor;
    }
}
