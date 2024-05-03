import pathlib
import jpype
import jpype.imports
from jpype.types import *
import importlib.resources
from typing import cast, List, Type, TypeVar, Callable, Union, TYPE_CHECKING, Any
from ._jpype_type_conversions import PythonSupplier, ConstraintProviderFunction

if TYPE_CHECKING:
    # These imports require a JVM to be running, so only import if type checking
    from java.lang import ClassLoader
    from ai.timefold.solver.core.api.score.stream import (Constraint as _Constraint,
                                                          ConstraintFactory as _ConstraintFactory)

Solution_ = TypeVar('Solution_')
ProblemId_ = TypeVar('ProblemId_')
Score_ = TypeVar('Score_')

_compilation_queue: list[type] = []
_enterprise_installed: bool = False


def is_enterprise_installed() -> bool:
    global _enterprise_installed
    return _enterprise_installed


def extract_timefold_jars() -> list[str]:
    """Extracts and return a list of timefold Java dependencies

    Invoking this function extracts timefold Dependencies from the timefold.solver.jars module
    into a temporary directory and returns a list contains classpath entries for
    those dependencies. The temporary directory exists for the entire execution of the
    program.

    :return: None
    """
    global _enterprise_installed
    try:

        enterprise_dependencies = [str(importlib.resources.path('timefold.solver.enterprise.jars',
                                                                p.name).__enter__())
                                   for p in importlib.resources.files('timefold.solver.enterprise.jars').iterdir()
                                   if p.name.endswith(".jar")]
        _enterprise_installed = True
    except ModuleNotFoundError:
        enterprise_dependencies = []
        _enterprise_installed = False
    return [str(importlib.resources.path('timefold.solver.jars', p.name).__enter__())
            for p in importlib.resources.files('timefold.solver.jars').iterdir()
            if p.name.endswith(".jar")] + enterprise_dependencies


def init(*args, path: List[str] = None, include_timefold_jars: bool = True, log_level='INFO'):
    """Start the JVM. Throws a RuntimeError if it is already started.

    :param args: JVM args.
    :param path: If not None, a list of dependencies to use as the classpath. Default to None.
    :param include_timefold_jars: If True, add timefold jars to path. Default to True.
    :param log_level: What Timefold's log level should be set to.
                      Must be one of 'TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR'.
                      Defaults to 'INFO'
    :return: None
    """
    from jpyinterpreter import init
    if jpype.isJVMStarted():  # noqa
        raise RuntimeError('JVM already started. Maybe call init before timefold.solver.types imports?')
    if path is None:
        include_timefold_jars = True
        path = []
    if include_timefold_jars:
        path = path + extract_timefold_jars()
    if len(args) == 0:
        args = (jpype.getDefaultJVMPath(), '-Dlogback.level.ai.timefold={}'.format(log_level))  # noqa
    else:
        args = args + ('-Dlogback.level.ai.timefold={}'.format(log_level),)
    init(*args, path=path, include_translator_jars=False)


def ensure_init():
    """Start the JVM if it isn't started; does nothing otherwise

    Used by timefold to start the JVM when needed by a method, so
    users don't need to start the JVM themselves.

    :return: None
    """
    if jpype.isJVMStarted(): # noqa
        return
    else:
        init()


def set_class_output_directory(path: pathlib.Path):
    ensure_init()

    from ai.timefold.jpyinterpreter import PythonBytecodeToJavaBytecodeTranslator # noqa
    PythonBytecodeToJavaBytecodeTranslator.classOutputRootPath = path


solver_run_id_to_refs = dict()
"""Maps solver run id to solution clones it references"""


def _setup_solver_run(solver_run_id, solver_run_ref_list):
    solver_run_id_to_refs[solver_run_id] = solver_run_ref_list


def _cleanup_solver_run(solver_run_id):
    del solver_run_id_to_refs[solver_run_id]


def get_class(python_class: Union[Type, Callable]) -> JClass:
    """Return the Java Class for the given Python Class"""
    from java.lang import Object, Class
    from ai.timefold.jpyinterpreter.types.wrappers import OpaquePythonReference
    from jpyinterpreter import is_c_native, get_java_type_for_python_type

    if python_class is None:
        return cast(JClass, None)
    if isinstance(python_class, jpype.JClass):
        return cast(JClass, python_class).class_
    if isinstance(python_class, Class):
        return cast(JClass, python_class)
    if python_class == int:
        from java.lang import Integer
        return cast(JClass, Integer).class_
    if python_class == str:
        from java.lang import String
        return cast(JClass, String).class_
    if python_class == bool:
        from java.lang import Boolean
        return cast(JClass, Boolean).class_
    if hasattr(python_class, '_timefold_java_class'):
        return cast(JClass, python_class._timefold_java_class)
    if isinstance(python_class, type):
        return cast(JClass, get_java_type_for_python_type(python_class).getJavaClass())
    if is_c_native(python_class):
        return cast(JClass, OpaquePythonReference.class_)
    return cast(JClass, Object)


def get_asm_type(python_class: Union[Type, Callable]) -> Any:
    """Return the ASM type for the given Python Class"""
    from java.lang import Object, Class
    from ai.timefold.jpyinterpreter import AnnotationMetadata
    from ai.timefold.jpyinterpreter.types.wrappers import OpaquePythonReference
    from jpyinterpreter import is_c_native, get_java_type_for_python_type

    if python_class is None:
        return None
    if isinstance(python_class, jpype.JClass):
        return AnnotationMetadata.getValueAsType(python_class.class_.getName())
    if isinstance(python_class, Class):
        return AnnotationMetadata.getValueAsType(python_class.getName())
    if python_class == int:
        from java.lang import Integer
        return AnnotationMetadata.getValueAsType(Integer.class_.getName())
    if python_class == str:
        from java.lang import String
        return AnnotationMetadata.getValueAsType(String.class_.getName())
    if python_class == bool:
        from java.lang import Boolean
        return AnnotationMetadata.getValueAsType(Boolean.class_.getName())
    if hasattr(python_class, '_timefold_java_class'):
        return AnnotationMetadata.getValueAsType(python_class._timefold_java_class.getName())
    if isinstance(python_class, type):
        return AnnotationMetadata.getValueAsType(get_java_type_for_python_type(python_class).getJavaTypeInternalName())
    if is_c_native(python_class):
        return AnnotationMetadata.getValueAsType(OpaquePythonReference.class_.getName())
    return AnnotationMetadata.getValueAsType(Object.class_.getName())


def register_java_class(python_object: Solution_,
                        java_class: JClass) -> Solution_:
    python_object._timefold_java_class = java_class
    class_identifier = _get_class_identifier_for_object(python_object)
    class_identifier_to_java_class_map[class_identifier] = java_class
    return python_object


unique_class_id = 0
"""A unique identifier; used to guarantee the generated class java name is unique"""

class_identifier_to_java_class_map = dict()
"""Maps a class identifier to the corresponding java class (the last one defined with that identifier)"""


def _get_class_identifier_for_object(python_object):
    module = getattr(python_object, '__module__', '__main__')
    if module == '__main__':
        return python_object.__qualname__
    else:
        return f'{module}.{python_object.__qualname__}'


def _compose_unique_class_name(class_identifier: str):
    from jpype import JInt
    from ai.timefold.jpyinterpreter.util import JavaIdentifierUtils
    from ai.timefold.jpyinterpreter import PythonBytecodeToJavaBytecodeTranslator
    unique_class_name = f'org.jpyinterpreter.user.{class_identifier}'
    unique_class_name = JavaIdentifierUtils.sanitizeClassName(unique_class_name)
    number_of_instances = PythonBytecodeToJavaBytecodeTranslator.classNameToSharedInstanceCount.merge(
        unique_class_name, JInt(1), lambda a, b: JInt(a + b))
    if number_of_instances > 1:
        unique_class_name = f'{unique_class_name}$${number_of_instances}'
    return unique_class_name


def _does_class_define_eq_or_hashcode(python_class):
    return '__eq__' in python_class.__dict__ or '__hash__' in python_class.__dict__


class OverrideClassLoader:
    thread_class_loader: 'ClassLoader'

    def __enter__(self):
        from java.lang import Thread
        from ai.timefold.solver.python import PythonWrapperGenerator  # noqa
        class_loader = PythonWrapperGenerator.getClassLoaderForAliasMap(class_identifier_to_java_class_map)
        current_thread = Thread.currentThread()
        self.thread_class_loader = current_thread.getContextClassLoader()
        current_thread.setContextClassLoader(class_loader)
        return class_loader

    def __exit__(self, exc_type, exc_val, exc_tb):
        from java.lang import Thread
        current_thread = Thread.currentThread()
        current_thread.setContextClassLoader(self.thread_class_loader)


def compile_class(python_class: type) -> None:
    from jpyinterpreter import translate_python_class_to_java_class
    ensure_init()
    class_identifier = _get_class_identifier_for_object(python_class)
    out = translate_python_class_to_java_class(python_class).getJavaClass()
    class_identifier_to_java_class_map[class_identifier] = out


def _add_to_compilation_queue(python_class: type | PythonSupplier) -> None:
    global _compilation_queue
    _compilation_queue.append(python_class)


def _process_compilation_queue() -> None:
    global _compilation_queue

    while len(_compilation_queue) > 0:
        python_class = _compilation_queue.pop(0)

        if isinstance(python_class, PythonSupplier):
            python_class = python_class.get()

        compile_class(python_class)


def _to_constraint_java_array(python_list: List['_Constraint']) -> JArray:
    # reimport since the one in global scope is only for type checking
    import ai.timefold.solver.core.api.score.stream.Constraint as ActualConstraintClass
    out = jpype.JArray(ActualConstraintClass)(len(python_list))
    for i in range(len(python_list)):
        out[i] = python_list[i]
    return out


def _generate_constraint_provider_class(original_function: Callable[['_ConstraintFactory'], List['_Constraint']],
                                        wrapped_constraint_provider: Callable[['_ConstraintFactory'],
                                                                              List['_Constraint']]) -> JClass:
    ensure_init()
    from ai.timefold.solver.python import PythonWrapperGenerator  # noqa
    from ai.timefold.solver.core.api.score.stream import ConstraintProvider
    class_identifier = _get_class_identifier_for_object(original_function)
    out = PythonWrapperGenerator.defineConstraintProviderClass(
        _compose_unique_class_name(class_identifier),
        JObject(ConstraintProviderFunction(lambda cf: _to_constraint_java_array(wrapped_constraint_provider(cf))),
                ConstraintProvider))
    class_identifier_to_java_class_map[class_identifier] = out
    return out
