/*
 * Copyright (C) 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dagger.reflect;

import dagger.BindsInstance;
import dagger.Module;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static dagger.reflect.DaggerReflect.notImplemented;
import static dagger.reflect.Reflection.findQualifier;

final class ComponentBuilderInvocationHandler implements InvocationHandler {
  static <T> T create(Class<?> componentClass, Class<T> builderClass,
      BindingGraph.Builder graphBuilder, Set<Class<?>> modules, Set<Class<?>> dependencies) {
    if ((componentClass.getModifiers() & Modifier.PUBLIC) == 0) {
      // Instances of proxies cannot create another proxy instance where the second interface is
      // not public. This prevents proxies of builders from creating proxies of the component.
      throw new IllegalArgumentException("Component interface "
          + componentClass.getName()
          + " must be public in order to be reflectively created");
    }
    return builderClass.cast(
        Proxy.newProxyInstance(builderClass.getClassLoader(), new Class[] { builderClass },
            new ComponentBuilderInvocationHandler(componentClass, builderClass, graphBuilder,
                modules, dependencies)));
  }

  private final Class<?> componentClass;
  private final Class<?> builderClass;
  private final Map<Class<?>, Object> componentModuleInstances;
  private final Map<Class<?>, Object> componentDependencyInstances;
  private final BindingGraph.Builder graphBuilder;

  private ComponentBuilderInvocationHandler(Class<?> componentClass, Class<?> builderClass,
      BindingGraph.Builder graphBuilder, Set<Class<?>> componentModules,
      Set<Class<?>> componentDependencies) {
    this.componentClass = componentClass;
    this.builderClass = builderClass;

    // Start with all modules bound to null. Any remaining nulls will be assumed stateless.
    componentModuleInstances = new LinkedHashMap<>();
    for (Class<?> componentModule : componentModules) {
      componentModuleInstances.put(componentModule, null);
    }

    // Start with all dependencies as null. Any remaining nulls at creation time is an error.
    componentDependencyInstances = new LinkedHashMap<>();
    for (Class<?> componentDependency : componentDependencies) {
      componentDependencyInstances.put(componentDependency, null);
    }

    this.graphBuilder = graphBuilder;
  }

  @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    if (method.getDeclaringClass() == Object.class) {
      return method.invoke(proxy, args);
    }

    Class<?> returnType = method.getReturnType();
    Type[] parameterTypes = method.getGenericParameterTypes();
    Annotation[][] parameterAnnotations = method.getParameterAnnotations();

    if (returnType.equals(componentClass)) {
      if (parameterTypes.length != 0) {
        throw new IllegalStateException(); // TODO must be no-arg
      }
      for (Map.Entry<Class<?>, Object> entry : componentDependencyInstances.entrySet()) {
        if (entry.getValue() == null) {
          throw new IllegalStateException(); // TODO missing dependency
        }
        throw notImplemented("Component dependencies");
      }
      for (Map.Entry<Class<?>, Object> entry : componentModuleInstances.entrySet()) {
        ReflectiveModuleParser.parse(entry.getKey(), entry.getValue(), graphBuilder);
      }

      return ComponentInvocationHandler.create(componentClass, graphBuilder.build());
    }

    // TODO these are allowed to be void or a supertype
    if (returnType.equals(builderClass)) {
      if (parameterTypes.length != 1) {
        throw new IllegalStateException(); // TODO must be single arg
      }

      if (method.getAnnotation(BindsInstance.class) != null) {
        Key key = Key.of(findQualifier(parameterAnnotations[0]), parameterTypes[0]);
        Object instance = args[0];
        // TODO most nullable annotations don't have runtime retention. so maybe just always allow?
        //if (instance == null && !hasNullable(parameterAnnotations[0])) {
        //  throw new NullPointerException(); // TODO message
        //}
        graphBuilder.add(key, new Binding.Instance<>(instance));
      } else {
        Type parameterType = parameterTypes[0];
        if (parameterType instanceof Class<?>) {
          Class<?> parameterClass = (Class<?>) parameterType;
          if (parameterClass.getAnnotation(Module.class) != null) {
            if (componentModuleInstances.containsKey(parameterClass)) {
              componentModuleInstances.put(parameterClass, args[0]);
            } else {
              throw new IllegalStateException("Module "
                  + parameterClass.getName()
                  + " not declared in component "
                  + componentClass.getName());
            }
          } else {
            if (componentDependencyInstances.containsKey(parameterClass)) {
              componentDependencyInstances.put(parameterClass, args[0]);
            } else {
              throw new IllegalStateException("Dependency on "
                  + parameterClass.getName()
                  + " not declared in component "
                  + componentClass.getName());
            }
          }
        } else {
          throw new IllegalStateException(method.toString()); // TODO report unsupported method shape
        }
      }
      return proxy;
    }

    throw new IllegalStateException(method.toString()); // TODO report unsupported method shape
  }
}
