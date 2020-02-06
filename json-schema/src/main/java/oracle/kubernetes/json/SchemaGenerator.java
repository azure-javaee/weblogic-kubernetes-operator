// Copyright (c) 2018, 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import javax.annotation.Nonnull;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import org.joda.time.DateTime;

public class SchemaGenerator {

  private static final String EXTERNAL_CLASS = "external";

  private static final List<Class<?>> PRIMITIVE_NUMBERS =
      Arrays.asList(byte.class, short.class, int.class, long.class, float.class, double.class);

  private static final String JSON_SCHEMA_REFERENCE = "http://json-schema.org/draft-04/schema#";

  // A map of classes to their $ref values
  private Map<Class<?>, String> references = new HashMap<>();

  // A map of found classes to their definitions or the constant EXTERNAL_CLASS.
  private Map<Class<?>, Object> definedObjects = new HashMap<>();

  // a map of external class names to the external schema that defines them
  private Map<String, String> schemaUrls = new HashMap<>();

  // true if deprecated fields should be included in the schema
  private boolean includeDeprecated;

  // if true generate the additionalProperties field for each object. Defaults to true
  private boolean includeAdditionalProperties = true;

  // if true, the object fields are implemented as references to definitions
  private boolean supportObjectReferences = true;

  // if true, generate the top-level schema version reference
  private boolean includeSchemaReference = true;

  /**
   * Returns a pretty-printed string corresponding to a generated schema.
   *
   * @param schema a schema generated by a call to #generate
   * @return a string version of the schema
   */
  public static String prettyPrint(Object schema) {
    return new GsonBuilder().setPrettyPrinting().create().toJson(schema);
  }

  static <T, S> Map<T, S> loadCachedSchema(URL cacheUrl) throws IOException {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader schemaReader =
        new BufferedReader(new InputStreamReader(cacheUrl.openStream()))) {
      String inputLine;
      while ((inputLine = schemaReader.readLine()) != null) {
        sb.append(inputLine).append('\n');
      }
    }

    return fromJson(sb.toString());
  }

  @SuppressWarnings("unchecked")
  private static <T, S> Map<T, S> fromJson(String json) {
    return new Gson().fromJson(json, HashMap.class);
  }

  /**
   * Specifies the version of the Kubernetes schema to use.
   *
   * @param version a Kubernetes version string, such as "1.9.0"
   * @throws IOException if no schema for that version is cached.
   */
  public void useKubernetesVersion(String version) throws IOException {
    KubernetesSchemaReference reference = KubernetesSchemaReference.create(version);
    URL cacheUrl = reference.getKubernetesSchemaCacheUrl();
    if (cacheUrl == null) {
      throw new IOException("No schema cached for Kubernetes " + version);
    }

    addExternalSchema(reference.getKubernetesSchemaUrl(), cacheUrl);
  }

  /**
   * Adds external schema.
   *
   * @param schemaUrl Schema URL
   * @param cacheUrl Cached URL
   * @throws IOException IO exception
   */
  public void addExternalSchema(URL schemaUrl, URL cacheUrl) throws IOException {
    Map<String, Map<String, Object>> objectObjectMap = loadCachedSchema(cacheUrl);
    Map<String, Object> definitions = objectObjectMap.get("definitions");
    for (Map.Entry<String, Object> entry : definitions.entrySet()) {
      if (isDefinitionToUse(entry.getValue())) {
        schemaUrls.put(entry.getKey(), schemaUrl.toString());
      }
    }
  }

  @SuppressWarnings("unchecked")
  private boolean isDefinitionToUse(Object def) {
    Map<String, Object> definition = (Map<String, Object>) def;
    return !isDeprecated(definition.get("description"));
  }

  private boolean isDeprecated(Object description) {
    return description != null && description.toString().contains("Deprecated");
  }

  /**
   * Specifies whether deprecated fields should be included in the schema.
   *
   * @param includeDeprecated true to include deprecated fields. Defaults to false.
   */
  public void setIncludeDeprecated(boolean includeDeprecated) {
    this.includeDeprecated = includeDeprecated;
  }

  /**
   * Specifies whether the "additionalProperties" property will be specified to forbid properties
   * not in the schema.
   *
   * @param includeAdditionalProperties true to forbid unknown properties
   */
  public void setIncludeAdditionalProperties(boolean includeAdditionalProperties) {
    this.includeAdditionalProperties = includeAdditionalProperties;
  }

  /**
   * Specifies whether object fields will be implemented as references to existing definitions. If
   * false, nested objects will be described inline.
   *
   * @param supportObjectReferences true to reference definitions of object
   */
  public void setSupportObjectReferences(boolean supportObjectReferences) {
    this.supportObjectReferences = supportObjectReferences;
  }

  /**
   * Specifies whether top-level schema reference is included.
   *
   * @param includeSchemaReference true to include schema reference
   */
  public void setIncludeSchemaReference(boolean includeSchemaReference) {
    this.includeSchemaReference = includeSchemaReference;
  }

  /**
   * Generates an object representing a JSON schema for the specified class.
   *
   * @param someClass the class for which the schema should be generated
   * @return a map of maps, representing the computed JSON
   */
  public Map<String, Object> generate(Class someClass) {
    Map<String, Object> result = new HashMap<>();

    if (includeSchemaReference) {
      result.put("$schema", JSON_SCHEMA_REFERENCE);
    }
    generateObjectTypeIn(result, someClass);
    if (!definedObjects.isEmpty()) {
      Map<String, Object> definitions = new TreeMap<>();
      result.put("definitions", definitions);
      for (Class<?> type : definedObjects.keySet()) {
        if (!definedObjects.get(type).equals(EXTERNAL_CLASS)) {
          definitions.put(getDefinitionKey(type), definedObjects.get(type));
        }
      }
    }

    return result;
  }

  void generateFieldIn(Map<String, Object> map, Field field) {
    if (includeInSchema(field)) {
      map.put(getPropertyName(field), getSubSchema(field));
    }
  }

  private boolean includeInSchema(Field field) {
    return !isStatic(field) && !isVolatile(field) && !ignoreAsDeprecated(field);
  }

  private boolean isStatic(Field field) {
    return Modifier.isStatic(field.getModifiers());
  }

  private boolean isVolatile(Field field) {
    return Modifier.isVolatile(field.getModifiers());
  }

  private boolean ignoreAsDeprecated(Field field) {
    return !includeDeprecated && field.getAnnotation(Deprecated.class) != null;
  }

  private String getPropertyName(Field field) {
    SerializedName serializedName = field.getAnnotation(SerializedName.class);
    if (serializedName != null && serializedName.value().length() > 0) {
      return serializedName.value();
    } else {
      return field.getName();
    }
  }

  private Object getSubSchema(Field field) {
    Map<String, Object> result = new HashMap<>();

    SubSchemaGenerator sub = new SubSchemaGenerator(field);

    sub.generateTypeIn(result, field.getType());
    String description = getDescription(field);
    if (description != null) {
      result.put("description", description);
    }
    if (isString(field.getType())) {
      addStringRestrictions(result, field);
    }
    if (isNumeric(field.getType())) {
      addRange(result, field);
    }

    return result;
  }

  private boolean isString(Class<?> type) {
    return type.equals(String.class);
  }

  private boolean isDateTime(Class<?> type) {
    return type.equals(DateTime.class);
  }

  private boolean isNumeric(Class<?> type) {
    return Number.class.isAssignableFrom(type) || PRIMITIVE_NUMBERS.contains(type);
  }

  private String getDescription(Field field) {
    Description description = field.getAnnotation(Description.class);
    return description != null ? description.value() : null;
  }

  private String getDescription(Class<?> someClass) {
    Description description = someClass.getAnnotation(Description.class);
    return description != null ? description.value() : null;
  }

  private void addStringRestrictions(Map<String, Object> result, Field field) {
    Class<? extends Enum> enumClass = getEnumClass(field);
    if (enumClass != null) {
      addEnumValues(result, enumClass, getEnumQualifier(field));
    }

    String pattern = getPattern(field);
    if (pattern != null) {
      result.put("pattern", pattern);
    }
  }

  private Class<? extends java.lang.Enum> getEnumClass(Field field) {
    EnumClass annotation = field.getAnnotation(EnumClass.class);
    return annotation != null ? annotation.value() : null;
  }

  private String getEnumQualifier(Field field) {
    EnumClass annotation = field.getAnnotation(EnumClass.class);
    return annotation != null ? annotation.qualifier() : "";
  }

  private void addEnumValues(
      Map<String, Object> result, Class<? extends Enum> enumClass, String qualifier) {
    result.put("enum", getEnumValues(enumClass, qualifier));
  }

  private String getPattern(Field field) {
    Pattern pattern = field.getAnnotation(Pattern.class);
    return pattern == null ? null : pattern.value();
  }

  private void addRange(Map<String, Object> result, Field field) {
    Range annotation = field.getAnnotation(Range.class);
    if (annotation == null) {
      return;
    }

    if (annotation.minimum() > Integer.MIN_VALUE) {
      result.put("minimum", annotation.minimum());
    }
    if (annotation.maximum() < Integer.MAX_VALUE) {
      result.put("maximum", annotation.maximum());
    }
  }

  private void addReference(Class<?> type) {
    if (definedObjects.containsKey(type)) {
      return;
    }
    if (addedKubernetesClass(type)) {
      return;
    }

    Map<String, Object> definition = new HashMap<>();
    definedObjects.put(type, definition);
    references.put(type, "#/definitions/" + getDefinitionKey(type));
    generateObjectTypeIn(definition, type);
  }

  private boolean addedKubernetesClass(Class<?> theClass) {
    if (!theClass.getName().startsWith("io.kubernetes.client")) {
      return false;
    }

    for (String externalName : schemaUrls.keySet()) {
      if (KubernetesApiNames.matches(externalName, theClass)) {
        String schemaUrl = schemaUrls.get(externalName);
        definedObjects.put(theClass, EXTERNAL_CLASS);
        references.put(theClass, schemaUrl + "#/definitions/" + externalName);
        return true;
      }
    }

    return false;
  }

  private String getReferencePath(Class<?> type) {
    return references.get(type);
  }

  private String getDefinitionKey(Class<?> type) {
    return type.getSimpleName();
  }

  private void generateEnumTypeIn(Map<String, Object> result, Class<? extends Enum> enumType) {
    result.put("type", "string");
    addEnumValues(result, enumType, "");
  }

  private String[] getEnumValues(Class<?> enumType, String qualifier) {
    List<String> values = new ArrayList<>();
    Method qualifierMethod = getQualifierMethod(enumType, qualifier);

    for (Object enumConstant : enumType.getEnumConstants()) {
      if (satisfiesQualifier(enumConstant, qualifierMethod)) {
        values.add(enumConstant.toString());
      }
    }

    return values.toArray(new String[0]);
  }

  private Method getQualifierMethod(Class<?> enumType, String methodName) {
    try {
      Method method = enumType.getDeclaredMethod(methodName);
      if (!isBooleanMethod(method)) {
        return null;
      }
      return method;
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

  private boolean isBooleanMethod(Method method) {
    return method.getReturnType().equals(Boolean.class)
        || method.getReturnType().equals(boolean.class);
  }

  private boolean satisfiesQualifier(Object enumConstant, Method qualifier) {
    try {
      return qualifier == null || (Boolean) qualifier.invoke(enumConstant);
    } catch (IllegalAccessException | InvocationTargetException e) {
      return true;
    }
  }

  private void generateObjectTypeIn(Map<String, Object> result, Class<?> type) {
    if (isDateTime(type)) {
      result.put("type", "string");
      result.put("format", "date-time");
    } else {
      final Map<String, Object> properties = new HashMap<>();
      List<String> requiredFields = new ArrayList<>();
      result.put("type", "object");
      if (includeAdditionalProperties) {
        result.put("additionalProperties", "false");
      }
      Optional.ofNullable(getDescription(type)).ifPresent(s -> result.put("description", s));
      result.put("properties", properties);

      for (Field field : getPropertyFields(type)) {
        if (!isSelfReference(field)) {
          generateFieldIn(properties, field);
        }
        if (isRequired(field) && includeInSchema(field)) {
          requiredFields.add(getPropertyName(field));
        }
      }

      if (!requiredFields.isEmpty()) {
        result.put("required", requiredFields.toArray(new String[0]));
      }
    }
  }

  private Collection<Field> getPropertyFields(Class<?> type) {
    Set<Field> result = new HashSet<>();
    for (Class<?> cl = type; cl != null && !cl.equals(Object.class); cl = cl.getSuperclass()) {
      result.addAll(Arrays.asList(cl.getDeclaredFields()));
    }

    for (Iterator<Field> each = result.iterator(); each.hasNext(); ) {
      if (isSelfReference(each.next())) {
        each.remove();
      }
    }

    return result;
  }

  private boolean isSelfReference(Field field) {
    return field.getName().startsWith("this$");
  }

  private boolean isRequired(Field field) {
    return isPrimitive(field) || isNonNull(field);
  }

  private boolean isPrimitive(Field field) {
    return field.getType().isPrimitive();
  }

  private boolean isNonNull(Field field) {
    return field.getAnnotation(Nonnull.class) != null;
  }

  private class SubSchemaGenerator {
    Field field;

    SubSchemaGenerator(Field field) {
      this.field = field;
    }

    @SuppressWarnings("unchecked")
    private void generateTypeIn(Map<String, Object> result, Class<?> type) {
      if (type.equals(Boolean.class) || type.equals(Boolean.TYPE)) {
        result.put("type", "boolean");
      } else if (isNumeric(type)) {
        result.put("type", "number");
      } else if (isString(type)) {
        result.put("type", "string");
      } else if (type.isEnum()) {
        generateEnumTypeIn(result, (Class<? extends Enum>) type);
      } else if (type.isArray()) {
        this.generateArrayTypeIn(result, type);
      } else if (Collection.class.isAssignableFrom(type)) {
        generateCollectionTypeIn(result);
      } else {
        generateObjectFieldIn(result, type);
      }
    }

    private void generateObjectFieldIn(Map<String, Object> result, Class<?> type) {
      if (supportObjectReferences) {
        generateObjectReferenceIn(result, type);
      } else {
        generateObjectTypeIn(result, type);
      }
    }

    private void generateObjectReferenceIn(Map<String, Object> result, Class<?> type) {
      addReference(type);
      result.put("$ref", getReferencePath(type));
    }

    private void generateCollectionTypeIn(Map<String, Object> result) {
      Map<String, Object> items = new HashMap<>();
      result.put("type", "array");
      result.put("items", items);
      generateTypeIn(items, getGenericComponentType());
    }

    private Class<?> getGenericComponentType() {
      try {
        String typeName = field.getGenericType().getTypeName();
        String className = typeName.substring(typeName.indexOf("<") + 1, typeName.indexOf(">"));
        return field.getDeclaringClass().getClassLoader().loadClass(className);
      } catch (ClassNotFoundException e) {
        return Object.class;
      }
    }

    private void generateArrayTypeIn(Map<String, Object> result, Class<?> type) {
      Map<String, Object> items = new HashMap<>();
      result.put("type", "array");
      result.put("items", items);
      generateTypeIn(items, type.getComponentType());
    }
  }
}
