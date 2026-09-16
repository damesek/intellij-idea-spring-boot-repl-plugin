package com.baader.devrt;

import java.lang.reflect.*;
import java.util.*;

/** Produces ordinary Java source that can also be exported as a JUnit test. */
final class InvocationPlan {
    static String sourceType(Type type) {
        if(type instanceof Class<?> c){
            if(c.isArray())return sourceType(c.getComponentType())+"[]";
            for(Class<?> enclosing=c;enclosing!=null;enclosing=enclosing.getEnclosingClass())if(!Modifier.isPublic(enclosing.getModifiers())&&!enclosing.isPrimitive())throw new IllegalArgumentException("Type is not public: "+c.getName());
            if(c.getCanonicalName()==null)throw new IllegalArgumentException("Anonymous/local types cannot be replayed");return c.getCanonicalName();
        }
        if(type instanceof ParameterizedType p)return sourceType(p.getRawType())+"<"+String.join(",",Arrays.stream(p.getActualTypeArguments()).map(InvocationPlan::sourceType).toList())+">";
        if(type instanceof GenericArrayType a)return sourceType(a.getGenericComponentType())+"[]";
        throw new IllegalArgumentException("Resolve generic type variables/wildcards before replay: "+type.getTypeName());
    }
    static List<String> types(Method method){return Arrays.stream(method.getGenericParameterTypes()).map(InvocationPlan::sourceType).toList();}
    static String runtimeType(Type type){
        if(type instanceof Class<?> c)return c.isPrimitive()?boxed(c.getName()):c.getName();
        if(type instanceof ParameterizedType p)return runtimeType(p.getRawType())+"<"+String.join(",",Arrays.stream(p.getActualTypeArguments()).map(InvocationPlan::runtimeType).toList())+">";
        throw new IllegalArgumentException("Unsupported restore type: "+type.getTypeName());
    }
    static String boxed(String type){return switch(type){case "int"->"java.lang.Integer";case "long"->"java.lang.Long";case "double"->"java.lang.Double";case "float"->"java.lang.Float";case "boolean"->"java.lang.Boolean";case "byte"->"java.lang.Byte";case "short"->"java.lang.Short";case "char"->"java.lang.Character";default->type;};}
    static Map<String,String> definition(Method method,Class<?> beanType,String bean,String input,String expected) {
        List<String> types=types(method),args=new ArrayList<>();StringBuilder code=new StringBuilder();
        if(!types.isEmpty())code.append("var callMapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();\n");
        for(int i=0;i<types.size();i++){
            String arg="callArg"+i;args.add(arg);
            code.append(types.get(i)).append(' ').append(arg).append(" = callMapper.convertValue(input.get(\"arg").append(i)
                .append("\"), new com.fasterxml.jackson.core.type.TypeReference<").append(boxed(types.get(i))).append(">() {});\n");
        }
        String receiver=Modifier.isStatic(method.getModifiers())?sourceType(method.getDeclaringClass()):"ctx.getBean("+CaseJson.write(bean)+", "+sourceType(beanType)+".class)";
        String expression=receiver+"."+method.getName()+"("+String.join(", ",args)+")";
        if(method.getReturnType()==void.class){code.append(expression).append(";\n");expression="(Object) null";}
        return new LinkedHashMap<>(Map.of("input",input,"expected",expected,"type","java.util.Map<java.lang.String,java.lang.Object>","variable","input","code",code.toString(),"result-expression",expression));
    }
}
