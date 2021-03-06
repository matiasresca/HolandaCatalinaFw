package org.hcjf.utils;

import org.hcjf.names.Naming;
import org.hcjf.service.security.LazyPermission;
import org.hcjf.service.security.Permission;
import org.hcjf.service.security.SecurityPermissions;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class contains a set of utilities to optimize the introspection native methods.
 * @author javaito
 *
 */
public final class Introspection {

    private static final Pattern GETTER_METHODS_PATTERN = Pattern.compile("^(get|is)([1,A-Z]|[1,0-9])(.*)");
    private static final Pattern SETTER_METHODS_PATTERN = Pattern.compile("^(set)([1,A-Z]|[1,0-9])(.*)");

    private static final int SETTER_GETTER_FIRST_CHAR_FIELD_NAME_GROUP = 2;
    private static final int SETTER_GETTER_FIELD_NAME_GROUP = 3;

    private static final Map<String, Map<String, ? extends Invoker>> invokerCache = new HashMap<>();
    private static final Map<Class, Map<String, Accessors>> accessorsCache = new HashMap<>();

    /**
     * Return the value that is the result of invoke the specific getter method.
     * @param instance Instance to invoke the getter method.
     * @param getterName Specific getter name.
     * @param <O> Expected result type.
     * @return Return the value that is a result of the specific getter method.
     * @throws InvocationTargetException Invocation target exception.
     * @throws IllegalAccessException Illegal access exception.
     */
    public static <O extends Object> O get(Object instance, String getterName) throws InvocationTargetException, IllegalAccessException {
        return getGetters(instance.getClass()).get(getterName).get(instance);
    }

    /**
     * Return a list of values that are the results of invoke each of the specific getters.
     * @param instance Instance to invoke each of getters.
     * @param getters Specific getter names.
     * @return Return a list with each of the results.
     * @throws InvocationTargetException Invocation target exception.
     * @throws IllegalAccessException Illegal access exception.
     */
    public static List get(Object instance, String... getters) throws InvocationTargetException, IllegalAccessException {
        List result = new ArrayList();
        for(String getter : getters) {
            result.add(get(instance, getter));
        }
        return result;
    }

    /**
     * Creates a mpa using all the getters method of the class.
     * @param instance Instance to transform.
     * @return Map
     */
    public static Map<String, Object> toMap(Object instance) {
        return toMap(instance, (O)->O);
    }

    /**
     * Creates a mpa (String key and String value) using all the getters method of the class.
     * @param instance Instance to transform.
     * @return Map
     */
    public static Map<String, String> toStringsMap(Object instance) {
        return toMap(instance, (O)->O.toString());
    }

    /**
     * Creates a mpa using all the getters method of the class.
     * @param instance Instance to transform.
     * @param consumer Instance of the consumer to transform the values of the
     *                 instance to the expected values into the map.
     * @param <O> Expected return value into the map.
     * @return Map
     */
    public static <O extends Object> Map<String, O> toMap(Object instance, Consumer consumer) {
        Map<String, O> result = new HashMap<>();
        if(instance instanceof Map) {
            result = (Map<String, O>) instance;
        } else {
            Map<String, Getter> getters = getGetters(instance.getClass());
            Object value;
            for (String name : getters.keySet()) {
                try {
                    value = getters.get(name).get(instance);
                    if (value != null) {
                        result.put(name, (O) consumer.consume(value));
                    }
                } catch (Exception e) {
                }
            }
        }
        return result;
    }

    /**
     * Create an instance of the class from a map.
     * @param map Map with values.
     * @param clazz Instance class.
     * @param <O> Expected type.
     * @return Instance.
     * @throws IllegalAccessException Illegal access exception.
     * @throws InstantiationException Instantiation exception.
     */
    public static <O extends Object> O toInstance(Map<String, Object> map, Class<O> clazz) throws IllegalAccessException, InstantiationException {
        O result = null;
        try {
            result = clazz.getConstructor().newInstance();
        } catch (InvocationTargetException e) {
            throw new IllegalArgumentException("Unable to create instance", e);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Default constructor not found", e);
        }
        Map<String, Setter> setters = getSetters(clazz);
        for(String name : setters.keySet()) {
            if(map.containsKey(name)) {
                try {
                    setters.get(name).set(result, map.get(name));
                } catch (Exception ex){}
            }
        }
        return result;
    }

    /**
     * Return a map with all the methods founded in the class applying the filter, indexed
     * by the filter definition.
     * @param clazz Class to be inspected.
     * @param filter Filter to apply.
     * @param <I> Expected return value into the map.
     * @return Return the founded invokers.
     */
    public static <I extends Invoker> Map<String, I> getInvokers(Class clazz, InvokerFilter<I> filter) {
        Map<String, I> result = new HashMap<>();
        String invokerKey = getInvokerKey(clazz, filter);

        if(!clazz.equals(Object.class)) {
            synchronized (invokerCache) {
                if(!invokerCache.containsKey(invokerKey)) {
                    invokerCache.put(invokerKey, result);

                    if(clazz.getSuperclass() != null && !clazz.getSuperclass().equals(Objects.class)) {
                        result.putAll(getInvokers(clazz.getSuperclass(), filter));
                    }

                    for(Method method : clazz.getDeclaredMethods()) {
                        InvokerEntry<I> entry = filter.filter(method);
                        if(entry != null) {
                            result.put(entry.getKey(), entry.getInvoker());
                            for(String alias : entry.getAliases()) {
                                result.put(alias, entry.getInvoker());
                            }
                        }
                    }
                } else {
                    result = (Map<String, I>) invokerCache.get(invokerKey);
                }
            }
        }

        return Collections.unmodifiableMap(result);
    }

    /**
     * Return the key in order to index the filter result.
     * @param clazz Class to be filtered.
     * @param filter Filter
     * @return Filter key.
     */
    private static String getInvokerKey(Class clazz, InvokerFilter filter) {
        return clazz.getName() + filter.getClass().getName();
    }

    /**
     * Returns a map with the accessors instance for a specific resource.
     * @param clazz Resource class.
     * @return Accessors map.
     */
    public static synchronized Map<String, Accessors> getAccessors(Class clazz) {
        Map<String, Accessors> result = accessorsCache.get(clazz);

        if(result == null) {
            result = new HashMap<>();
            Map<String, Setter> setterMap = getSetters(clazz);
            Map<String, Getter> getterMap = getGetters(clazz);
            Set<String> keySet = new HashSet<>();
            keySet.addAll(setterMap.keySet());
            keySet.addAll(getterMap.keySet());

            for (String key : keySet) {
                result.put(key, new Accessors(key, getterMap.get(key), setterMap.get(key)));
            }
            accessorsCache.put(clazz, result);
        }

        return Collections.unmodifiableMap(result);
    }

    /**
     * Return a map with all the getters accessor instances indexed by the expected name of the
     * field that represents each accessor. The fields represented by the name can exists or no.
     * The accessor instances contains all the information about accessor method and the annotation
     * bounded to the method.
     * The found methods comply with the following regular expression and conditions:
     *  - ^(get|is)([1,A-Z]|[1,0-9])(.*)
     *  - must return something distinct to void type
     *  - without parameters
     *  - must be a public method
     * @param clazz Class definition to found the getters method.
     * @param namingImpl Name of the naming implementation.
     * @return All the accessors founded indexed by the possible field name.
     */
    public static Map<String, Getter> getGetters(Class clazz, String namingImpl) {
        Map<String, Getter> getters = getGetters(clazz);
        Map<String, Getter> result = new HashMap<>();

        for(String name : getters.keySet()) {
            result.put(Naming.normalize(namingImpl, name), getters.get(name));
        }

        return Collections.unmodifiableMap(result);
    }

    /**
     * Return a map with all the getters accessor instances indexed by the expected name of the
     * field that represents each accessor. The fields represented by the name can exists or no.
     * The accessor instances contains all the information about accessor method and the annotation
     * bounded to the method.
     * The found methods comply with the following regular expression and conditions:
     *  - ^(get|is)([1,A-Z]|[1,0-9])(.*)
     *  - must return something distinct to void type
     *  - without parameters
     *  - must be a public method
     * @param clazz Class definition `to found the getters method.
     * @return All the accessors founded indexed by the possible field name.
     */
    public static Map<String, Getter> getGetters(Class clazz) {
        Map<String, Getter> result = getInvokers(clazz, method -> {
            InvokerEntry<Getter> result1 = null;
            Matcher matcher;
            String fieldName;
            if(Modifier.isPublic(method.getModifiers())) {
                matcher = GETTER_METHODS_PATTERN.matcher(method.getName());
                if(matcher.matches() && !method.getReturnType().equals(Void.TYPE) &&
                        method.getParameterTypes().length == 0) {
                    fieldName = matcher.group(SETTER_GETTER_FIRST_CHAR_FIELD_NAME_GROUP).toLowerCase() +
                            matcher.group(SETTER_GETTER_FIELD_NAME_GROUP);
                    result1 = new InvokerEntry<>(fieldName, new Getter(method.getDeclaringClass(), fieldName, method));
                }
            }
            return result1;
        });
        return Collections.unmodifiableMap(result);
    }

    /**
     * Return a map with all the setters accessor instances indexed by the expected name of the
     * field that represents each accessor. The fields represented by the name can exists or no.
     * The accessor instances contains all the information about accessor method and the annotation
     * bounded to the method.
     * The found methods comply with the following regular expression and conditions:
     *  - ^(set)([1,A-Z]|[1,0-9])(.*)
     *  - must return void type.
     *  - with only one parameter
     *  - must be a public method
     * @param clazz Class definition to found the setter method.
     * @param namingImpl Naming service implementation.
     * @return All the accessors founded indexed by the possible field name.
     */
    public static Map<String, Setter> getSetters(Class clazz, String namingImpl) {
        Map<String, Setter> setters = getSetters(clazz);
        Map<String, Setter> result = new HashMap<>();

        for(String name : setters.keySet()) {
            result.put(Naming.normalize(namingImpl, name), setters.get(name));
        }

        return Collections.unmodifiableMap(result);
    }

    /**
     * Return a map with all the setters accessor instances indexed by the expected name of the
     * field that represents each accessor. The fields represented by the name can exists or no.
     * The accessor instances contains all the information about accessor method and the annotation
     * bounded to the method.
     * The found methods comply with the following regular expression and conditions:
     *  - ^(set)([1,A-Z]|[1,0-9])(.*)
     *  - must return void type.
     *  - with only one parameter
     *  - must be a public method
     * @param clazz Class definition to found the setter method.
     * @return All the accessors founded indexed by the possible field name.
     */
    public static Map<String, Setter> getSetters(Class clazz) {
        Map<String, Setter> result = getInvokers(clazz, method -> {
            InvokerEntry<Setter> result1 = null;
            Matcher matcher;
            String fieldName;
            if(Modifier.isPublic(method.getModifiers())) {
                matcher = SETTER_METHODS_PATTERN.matcher(method.getName());
                if(matcher.matches() && method.getReturnType().equals(Void.TYPE) &&
                        method.getParameterTypes().length == 1) {
                    fieldName = matcher.group(SETTER_GETTER_FIRST_CHAR_FIELD_NAME_GROUP).toLowerCase() +
                            matcher.group(SETTER_GETTER_FIELD_NAME_GROUP);
                    result1 = new InvokerEntry<>(fieldName, new Setter(method.getDeclaringClass(), fieldName, method));
                }
            }
            return result1;
        });
        return Collections.unmodifiableMap(result);
    }

    public static abstract class Invoker {

        private final Class implementationClass;
        private final Method method;
        private final Map<Class<? extends Annotation>, List<Annotation>> annotationsMap;
        private boolean containsPermission;

        public Invoker(Class implementationClass, Method method) {
            this.implementationClass = implementationClass;
            this.method = method;
            this.annotationsMap = new HashMap<>();
            List<Annotation> annotationList;
            for(Annotation annotation : method.getAnnotations()) {
                Class annotationClass = null;
                for(Class interfaceClass : annotation.getClass().getInterfaces()) {
                    if(Annotation.class.isAssignableFrom(interfaceClass)) {
                        annotationClass = interfaceClass;
                    }
                }
                annotationList = annotationsMap.get(annotationClass);
                if(annotationList == null) {
                    annotationList = new ArrayList<>();
                    annotationsMap.put(annotationClass, annotationList);
                }
                annotationList.add(annotation);

                if(annotationClass.equals(Permission.class)) {
                    SecurityPermissions.publishPermission(implementationClass, ((Permission)annotation).value());
                    containsPermission = true;
                } else if(annotationClass.equals(LazyPermission.class)) {
                    SecurityPermissions.publishPermission(implementationClass, ((LazyPermission)annotation).value());
                }
            }
        }

        /**
         * Return the the name of the class when the method is implemented.
         * @return Implementation class name.
         */
        public final Class getImplementationClass() {
            return implementationClass;
        }

        /**
         * Return the name of the accessor method.
         * @return Name of the accessor method.
         */
        public final Method getMethod() {
            return method;
        }

        /**
         * Verify if exists an instance of the annotation class associated to the accessor method.
         * @param annotationClass Annotation class.
         * @return True if the instance exists and false in the otherwise.
         */
        public final boolean isAnnotationPresent(Class<? extends Annotation> annotationClass) {
            return annotationsMap.containsKey(annotationClass);
        }

        /**
         * Returns the instance of the annotation class associated to the accessor method, or null if
         * the annotation doesn't exist.
         * This method return the first instance of the list of annotations.
         * @param annotationClass Annotation class.
         * @param <A> Expected annotation type.
         * @return Annotation instance or null.
         */
        public final <A extends Annotation> A getAnnotation(Class<? extends A> annotationClass) {
            A result = null;
            if(annotationsMap.containsKey(annotationClass)) {
                result = (A) annotationsMap.get(annotationClass).get(0);
            }
            return result;
        }

        /**
         * Returns the list of the annotation instances associated to the invoker, or null if
         * the annotation class is not present into the invoker.
         * @param annotationClass Annotation class.
         * @param <A> Expected annotation type.
         * @return Unmodifiable list of annotation instances.
         */
        public final <A extends Annotation> List<A> getAnnotations(Class<? extends A> annotationClass) {
            List<Annotation> result = new ArrayList<>();
            if(annotationsMap.containsKey(annotationClass)) {
                result = Collections.unmodifiableList(annotationsMap.get(annotationClass));
            }
            return (List<A>) result;
        }

        /**
         * Return an unmodifiable map with all the annotation instances associated to the method indexed
         * by the class of the each annotation instance.
         * @return Unmodifiable map.
         */
        public final Map<Class<? extends Annotation>, List<Annotation>> getAnnotationsMap() {
            return Collections.unmodifiableMap(annotationsMap);
        }

        /**
         * Wrapper method to get the storage method.
         * @param instance Instance to get the method.
         * @param params Method parameters.
         * @return Invocation result.
         * @throws InvocationTargetException Invocation Target Exception
         * @throws IllegalAccessException Illegal Access Exception
         */
        public Object invoke(Object instance, Object... params) throws InvocationTargetException, IllegalAccessException {
            if(containsPermission) {
                for (Permission permission : getAnnotations(Permission.class)) {
                    SecurityPermissions.checkPermission(instance.getClass(), permission.value());
                }
            }
            return getMethod().invoke(instance, params);
        }
    }

    /**
     * This class contains the instances of getter and setter for a specific
     * resource
     */
    public static final class Accessors {

        private final String resourceName;
        private final Getter getter;
        private final Setter setter;

        public Accessors(String resourceName, Getter getter, Setter setter) {
            this.resourceName = resourceName;
            this.getter = getter;
            this.setter = setter;
        }

        /**
         * Returns the getter instance.
         * @return Getter instance.
         */
        public Getter getGetter() {
            return getter;
        }

        /**
         * Returns the setter instance.
         * @return Setter instance.
         */
        public Setter getSetter() {
            return setter;
        }

        /**
         * Verify if exists an instance of the annotation class associated to the accessor method.
         * @param annotationClass Annotation class.
         * @return True if the instance exists and false in the otherwise
         */
        public boolean isAnnotationPresent(Class<? extends Annotation> annotationClass) {
            boolean result = false;

            if(getter != null) {
                result |= getter.isAnnotationPresent(annotationClass);
            }

            if(setter != null) {
                result |= setter.isAnnotationPresent(annotationClass);
            }

            return result;
        }

        /**
         * Returns the instance of the annotation class associated to the accessor method, or null if
         * the annotation doesn't exist.
         * This method return the first instance of the list of annotations.
         * @param annotationClass Annotation class.
         * @param <A> Expected annotation type.
         * @return Annotation instance or null.
         */
        public final <A extends Annotation> A getAnnotation(Class<? extends A> annotationClass) {
            A result = null;

            if(getter != null) {
                result = getter.getAnnotation(annotationClass);
            }

            if(result == null && setter != null) {
                result = setter.getAnnotation(annotationClass);
            }

            return result;
        }

        /**
         * Returns the list of the annotation instances associated to the invoker, or null if
         * the annotation class is not present into the invoker.
         * @param annotationClass Annotation class.
         * @param <A> Expected annotation type.
         * @return Unmodifiable list of annotation instances.
         */
        public final <A extends Annotation> List<A> getAnnotations(Class<? extends A> annotationClass) {
            List<Annotation> result = new ArrayList<>();

            if(getter != null) {
                result.addAll(getter.getAnnotations(annotationClass));
            }

            if(setter != null) {
                result.addAll(setter.getAnnotations(annotationClass));
            }

            return (List<A>) result;
        }

        /**
         * Return an unmodifiable map with all the annotation instances associated to the method indexed
         * by the class of the each annotation instance.
         * @return Unmodifiable map.
         */
        public final Map<Class<? extends Annotation>, List<Annotation>> getAnnotationsMap() {
            Map<Class<? extends Annotation>, List<Annotation>> result = new HashMap<>();

            if(getter != null) {
                result.putAll(getter.getAnnotationsMap());
            }

            if(setter != null) {
                result.putAll(setter.getAnnotationsMap());
            }

            return result;
        }
    }

    /**
     * This class groups all the information about getter/setter accessor methods.
     */
    public static abstract class Accessor extends Invoker {

        private final String resourceName;

        protected Accessor(Class implementationClass, String resourceName, Method method) {
            super(implementationClass, method);
            this.resourceName = resourceName;
        }

        /**
         * Return the name of the resource, this name is a representation based on the
         * accessor method name. if the accessor name is 'getA' then the resource name is 'a'.
         * @return Resource name.
         */
        public final String getResourceName() {
            return resourceName;
        }

    }

    /**
     * Sub class of the accessor that represents only the getter accessors.
     */
    public static class Getter extends Accessor {

        private final Class returnType;
        private ParameterizedType parameterParameterizedType;
        private final Class returnKeyType;
        private final Class returnCollectionType;

        public Getter(Class implementationClass, String resourceName, Method method) {
            super(implementationClass, resourceName, method);
            returnType = method.getReturnType();
            parameterParameterizedType = null;

            if (method.getGenericReturnType() instanceof ParameterizedType) {
                parameterParameterizedType = (ParameterizedType) method.getGenericReturnType();
                if(Collection.class.isAssignableFrom(returnType)) {
                    returnKeyType = null;
                    if(parameterParameterizedType.getActualTypeArguments()[0] instanceof Class) {
                        returnCollectionType = (Class) parameterParameterizedType.getActualTypeArguments()[0];
                    } else if(parameterParameterizedType.getActualTypeArguments()[0] instanceof TypeVariable) {
                        returnCollectionType = (Class) ((TypeVariable)parameterParameterizedType.getActualTypeArguments()[0]).getBounds()[0];
                    } else {
                        returnCollectionType = (Class) ((ParameterizedType)parameterParameterizedType.getActualTypeArguments()[0]).getRawType();
                    }
                } else if(Map.class.isAssignableFrom(returnType)) {
                    if(parameterParameterizedType.getActualTypeArguments()[0] instanceof Class) {
                        returnKeyType = (Class) parameterParameterizedType.getActualTypeArguments()[0];
                    } else if(parameterParameterizedType.getActualTypeArguments()[0] instanceof TypeVariable) {
                        returnKeyType = (Class) ((TypeVariable)parameterParameterizedType.getActualTypeArguments()[0]).getBounds()[0];
                    } else {
                        returnKeyType = (Class) ((ParameterizedType)parameterParameterizedType.getActualTypeArguments()[0]).getRawType();
                    }
                    if(parameterParameterizedType.getActualTypeArguments()[1] instanceof Class) {
                        returnCollectionType = (Class) parameterParameterizedType.getActualTypeArguments()[1];
                    } else if(parameterParameterizedType.getActualTypeArguments()[1] instanceof TypeVariable) {
                        returnCollectionType = (Class) ((TypeVariable)parameterParameterizedType.getActualTypeArguments()[1]).getBounds()[0];
                    } else {
                        returnCollectionType = (Class) ((ParameterizedType)parameterParameterizedType.getActualTypeArguments()[1]).getRawType();
                    }
                } else {
                    returnKeyType = null;
                    returnCollectionType = null;
                }
            } else {
                returnKeyType = null;
                returnCollectionType = null;
            }
        }

        /**
         * Reflection invoked by the underlying method to obtain the resource value.
         * @param instance Instance to do reflection.
         * @param <O> Expected result type for the client.
         * @return Resource value.
         * @throws InvocationTargetException Invocation target exception.
         * @throws IllegalAccessException Illegal access exception.
         */
        public <O extends Object> O get(Object instance) throws InvocationTargetException, IllegalAccessException {
            return (O) invoke(instance);
        }

        /**
         * Return the result type of the getter method.
         * @return Result type.
         */
        public final Class getReturnType() {
            return returnType;
        }

        /**
         * Returns the parameterized type that corresponds with the parameter type.
         * @return Parameterized parameter type.
         */
        public final ParameterizedType getParameterParameterizedType() {
            return parameterParameterizedType;
        }

        /**
         * If the result type is assignable to map class then this method return
         * the key type of the result type.
         * @return Key type of the map.
         */
        public final Class getReturnKeyType() {
            return returnKeyType;
        }

        /**
         * If the result type if assignable to collection or map this this mehtod return
         * the collection types of the result type.
         * @return Collection type of the result type.
         */
        public final Class getReturnCollectionType() {
            return returnCollectionType;
        }
    }

    /**
     * Sub class of the accessor that represents only the setter accessors.
     */
    public static class Setter extends Accessor {

        private final Class parameterType;
        private ParameterizedType parameterParameterizedType;
        private final Class parameterKeyType;
        private final Class parameterCollectionType;

        public Setter(Class implementationClass, String resourceName, Method method) {
            super(implementationClass, resourceName, method);
            this.parameterType = method.getParameterTypes()[0];
            this.parameterParameterizedType = null;

            if(method.getGenericParameterTypes()[0] instanceof ParameterizedType) {
                parameterParameterizedType = (ParameterizedType) method.getGenericParameterTypes()[0];
                if(Collection.class.isAssignableFrom(parameterType)) {
                    parameterKeyType = null;
                    if(parameterParameterizedType.getActualTypeArguments()[0] instanceof Class) {
                        parameterCollectionType = (Class) parameterParameterizedType.getActualTypeArguments()[0];
                    } else if(parameterParameterizedType.getActualTypeArguments()[0] instanceof TypeVariable) {
                        parameterCollectionType = (Class) ((TypeVariable)parameterParameterizedType.getActualTypeArguments()[0]).getBounds()[0];
                    } else {
                        parameterCollectionType = (Class) ((ParameterizedType) parameterParameterizedType.getActualTypeArguments()[0]).getRawType();
                    }
                } else if(Map.class.isAssignableFrom(parameterType)) {
                    if(parameterParameterizedType.getActualTypeArguments()[0] instanceof Class) {
                        parameterKeyType = (Class) parameterParameterizedType.getActualTypeArguments()[0];
                    } else if(parameterParameterizedType.getActualTypeArguments()[0] instanceof TypeVariable) {
                        parameterKeyType = (Class) ((TypeVariable)parameterParameterizedType.getActualTypeArguments()[0]).getBounds()[0];
                    } else {
                        parameterKeyType = (Class) ((ParameterizedType)parameterParameterizedType.getActualTypeArguments()[0]).getRawType();
                    }

                    if(parameterParameterizedType.getActualTypeArguments()[1] instanceof Class) {
                        parameterCollectionType = (Class) parameterParameterizedType.getActualTypeArguments()[1];
                    } else if(parameterParameterizedType.getActualTypeArguments()[1] instanceof TypeVariable) {
                        parameterCollectionType = (Class) ((TypeVariable)parameterParameterizedType.getActualTypeArguments()[1]).getBounds()[0];
                    } else {
                        parameterCollectionType = (Class) ((ParameterizedType)parameterParameterizedType.getActualTypeArguments()[1]).getRawType();
                    }
                } else {
                    parameterKeyType = null;
                    parameterCollectionType = null;
                }
            } else {
                parameterKeyType = null;
                parameterCollectionType = null;
            }
        }

        /**
         * Reflection invoked by the underlying method to set the resource value.
         * @param instance Instance to do reflection.
         * @param value Parameter value.
         * @throws InvocationTargetException Invocation target exception.
         * @throws IllegalAccessException Illegal access exception.
         */
        public void set(Object instance, Object value) throws InvocationTargetException, IllegalAccessException {
            invoke(instance, value);
        }

        /**
         * Return the parameter type of the setter method.
         * @return Parameter type.
         */
        public final Class getParameterType() {
            return parameterType;
        }

        /**
         * Returns the parameterized type that corresponds with the parameter type.
         * @return Parameterized parameter type.
         */
        public final ParameterizedType getParameterParameterizedType() {
            return parameterParameterizedType;
        }

        /**
         * If the parameter type is assignable to map class then this method
         * return the key type of the parameter type.
         * @return Key type.
         */
        public final Class getParameterKeyType() {
            return parameterKeyType;
        }

        /**
         * If the parameter type is assignable to map class or collection class then this
         * method return the collection type of the parameter type.
         * @return Collection type.
         */
        public final Class getParameterCollectionType() {
            return parameterCollectionType;
        }
    }

    /**
     * This interface must be implemented to found some kind of methods implemented
     * in the any class.
     * @param <I> Invoker type.
     */
    public interface InvokerFilter<I extends Invoker> {

        /**
         * This method will be called for each method of the filtered class.
         * @param method Declared method.
         * @return Return the entry or null if the method does not comply with the rule
         */
        public InvokerEntry<I> filter(Method method);

    }

    /**
     * This class represents the object returned by the invoker filter.
     * @param <I> Invoker type.
     */
    public static class InvokerEntry<I extends Invoker> {

        private final String key;
        private final I invoker;
        private final String[] aliases;

        public InvokerEntry(String key, I invoker, String... aliases) {
            this.key = key;
            this.invoker = invoker;
            this.aliases = aliases;
        }

        /**
         * Return the key of the entry.
         * @return Key of the entry.
         */
        public String getKey() {
            return key;
        }

        /**
         * Return the value of the entry.
         * @return Value of the entry.
         */
        public I getInvoker() {
            return invoker;
        }

        /**
         * Return the aliases array.
         * @return Aliases array.
         */
        public String[] getAliases() {
            return aliases;
        }
    }

    /**
     * This interface represents a possible implementation of the way to consume
     * a getter method of the some kind of object.
     */
    public interface Consumer {

        /**
         * The implementation of this method consume the value a return
         * the same value or other instance based on this value.
         * @param value Instance to consume.
         * @return Return a new representation of the value or the same object
         * depends the consumer implementation.
         */
        public Object consume(Object value);

    }

}
