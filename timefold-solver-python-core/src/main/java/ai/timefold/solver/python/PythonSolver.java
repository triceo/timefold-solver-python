package ai.timefold.solver.python;

import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

import ai.timefold.jpyinterpreter.CPythonBackedPythonInterpreter;
import ai.timefold.jpyinterpreter.types.wrappers.OpaquePythonReference;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;

@SuppressWarnings("unused")
public class PythonSolver {

    /**
     * If true, only use Java setters and only go back to CPython for cloning.
     * If false, invoking a Java setter also invoke the CPython setter.
     */
    public static boolean onlyUseJavaSetters = false;

    public static Map<Number, Object> getNewReferenceMap() {
        return new MirrorWithExtrasMap<>(CPythonBackedPythonInterpreter.pythonObjectIdToConvertedObjectMap);
    }

    public static Object wrapProblem(Class<?> solutionClass, OpaquePythonReference problem) {
        try {
            final boolean onlyUseJavaSettersForThisInstance = onlyUseJavaSetters;
            onlyUseJavaSetters = false;
            PythonObject out = (PythonObject) PythonWrapperGenerator.wrap(solutionClass, problem,
                    getNewReferenceMap(),
                    onlyUseJavaSettersForThisInstance ? PythonWrapperGenerator.NONE_PYTHON_SETTER
                            : PythonWrapperGenerator.pythonObjectIdAndAttributeSetter);
            out.visitIds(out.get__timefold_reference_map());

            // Mirror the reference map (not pass a reference to it)
            // so Score + list variables can be safely garbage collected in Python
            // (if it was not mirrored, reading the python object would add entries for them to the map,
            //  which is used when cloning. If score/list variable was garbage collected by Python, another
            //  Python Object can have the same id, leading to the old value in the map being returned,
            //  causing an exception (or worse, a subtle bug))
            out.readFromPythonObject(Collections.newSetFromMap(new IdentityHashMap<>()),
                    new MirrorWithExtrasMap<>(out.get__timefold_reference_map()));
            return out;
        } catch (Throwable t) {
            throw new TimefoldException("A problem occurred when wrapping the python problem (" +
                    PythonWrapperGenerator.getPythonObjectString(problem) +
                    "). Maybe an annotation was passed an incorrect type " +
                    "(for example, @problem_fact_collection_property(str) " +
                    " on a function that return a list of int).", t);
        }
    }

    public static Object wrapFact(Class<?> factClass, OpaquePythonReference fact, Map<Number, Object> referenceMap) {
        try {
            // We are wrapping a fact; so setters will not be used
            PythonObject out = (PythonObject) PythonWrapperGenerator.wrap(factClass, fact,
                    referenceMap,
                    PythonWrapperGenerator.NONE_PYTHON_SETTER);
            out.visitIds(out.get__timefold_reference_map());

            // Mirror the reference map (not pass a reference to it)
            // so Score + list variables can be safely garbage collected in Python
            // (if it was not mirrored, reading the python object would add entries for them to the map,
            //  which is used when cloning. If score/list variable was garbage collected by Python, another
            //  Python Object can have the same id, leading to the old value in the map being returned,
            //  causing an exception (or worse, a subtle bug))
            out.readFromPythonObject(Collections.newSetFromMap(new IdentityHashMap<>()),
                    new MirrorWithExtrasMap<>(out.get__timefold_reference_map()));
            return out;
        } catch (Throwable t) {
            throw new TimefoldException("A problem occurred when wrapping the python fact (" +
                    PythonWrapperGenerator.getPythonObjectString(fact) + ").\n"
                    + "Maybe an annotation was passed an incorrect type " +
                    "(for example, @problem_fact_collection_property(str) " +
                    " on a function that returns a list of int).", t);
        }
    }

    public static ConstraintProvider createConstraintProvider(Class<? extends ConstraintProvider> constraintProviderClass)
            throws NoSuchMethodException,
            InvocationTargetException, InstantiationException, IllegalAccessException {
        return constraintProviderClass.getConstructor().newInstance();
    }
}
