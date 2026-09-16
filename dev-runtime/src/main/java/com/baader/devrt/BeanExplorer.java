package com.baader.devrt;

import java.lang.reflect.*;
import java.util.*;

/** Reads definitions and existing singletons. Discovery never calls getBean or creates a lazy bean. */
final class BeanExplorer {
    static Object factory(Object context) throws Exception {
        if(context==null)throw new IllegalStateException("Spring context is not ready");
        return context.getClass().getMethod("getBeanFactory").invoke(context);
    }
    static Class<?> type(Object context,String name) throws Exception {
        Object type=context.getClass().getMethod("getType",String.class,boolean.class).invoke(context,name,false);
        if(!(type instanceof Class<?> value))throw new IllegalArgumentException("Bean type is unavailable without initialization: "+name);
        while(value.getName().contains("$$SpringCGLIB$$")&&value.getSuperclass()!=null)value=value.getSuperclass();
        return value;
    }
    static Object invoke(Object target,String method,Class<?>[] types,Object... args) throws Exception {
        return target.getClass().getMethod(method,types).invoke(target,args);
    }
    static List<String> names(Object context,Class<?> type) throws Exception {
        Set<String> names=new TreeSet<>(Arrays.asList((String[])invoke(context,"getBeanNamesForType",new Class<?>[]{Class.class,boolean.class,boolean.class},type,true,false)));
        Object factory=factory(context);
        for(String name:(String[])invoke(factory,"getSingletonNames",new Class<?>[0])){
            Object existing=invoke(factory,"getSingleton",new Class<?>[]{String.class},name);
            if(existing!=null&&type.isAssignableFrom(targetType(context,existing)))names.add(name);
        }
        return names.stream().limit(256).toList();
    }
    static Class<?> targetType(Object context,Object instance){
        try{return (Class<?>)Class.forName("org.springframework.aop.support.AopUtils",false,AppClassPath.loader(context)).getMethod("getTargetClass",Object.class).invoke(null,instance);}
        catch(ReflectiveOperationException ignored){return instance.getClass();}
    }
    static Class<?> invocationType(Class<?> type,Method method){
        if(!Proxy.isProxyClass(type))return type;
        for(Class<?> api:type.getInterfaces())if(Modifier.isPublic(api.getModifiers()))try{
            api.getMethod(method.getName(),method.getParameterTypes());InvocationPlan.sourceType(api);return api;
        }catch(NoSuchMethodException|IllegalArgumentException ignored){}
        throw new IllegalArgumentException("Method is not exposed by a public interface of this JDK proxy");
    }
    static Map<String,Object> list(Object context,String query,int offset) throws Exception {
        Object factory=factory(context);
        if(query.length()>256||offset<0)throw new IllegalArgumentException("Invalid bean search");
        Set<String> all=new TreeSet<>(Arrays.asList((String[])invoke(factory,"getBeanDefinitionNames",new Class<?>[0])));
        all.addAll(Arrays.asList((String[])invoke(factory,"getSingletonNames",new Class<?>[0])));String[] names=all.toArray(String[]::new);
        List<Map<String,Object>> matches=new ArrayList<>();
        String filter=query.toLowerCase(Locale.ROOT);
        for(String name:names){
            String type="";try{type=type(context,name).getName();}catch(Exception ignored){}
            if((name+" "+type).toLowerCase(Locale.ROOT).contains(filter))matches.add(Map.of("name",name,"type",type));
        }
        return Map.of("beans-json",CaseJson.write(matches.stream().skip(offset).limit(100).toList()),"total",matches.size(),"offset",offset);
    }
    static Map<String,Object> info(Object context,String name) throws Exception {
        if(name==null||name.length()>512)throw new IllegalArgumentException("Choose a bean name");
        Object factory=factory(context),definition;
        try{definition=invoke(factory,"getBeanDefinition",new Class<?>[]{String.class},name);}
        catch(InvocationTargetException failure){if(failure.getCause().getClass().getName().equals("org.springframework.beans.factory.NoSuchBeanDefinitionException"))definition=null;else throw failure;}
        Class<?> type=type(context,name);
        Map<String,Object> info=new LinkedHashMap<>();info.put("name",name);info.put("type",type.getName());
        for(String[] field:new String[][]{{"scope","getScope"},{"primary","isPrimary"},{"lazy","isLazyInit"},{"resource","getResourceDescription"},{"factoryBean","getFactoryBeanName"},{"factoryMethod","getFactoryMethodName"}})
            try{info.put(field[0],invoke(definition,field[1],new Class<?>[0]));}catch(Exception ignored){}
        if("".equals(info.get("scope")))info.put("scope","singleton");
        for(String[] field:new String[][]{{"dependencies","getDependenciesForBean"},{"dependents","getDependentBeans"},{"aliases","getAliases"}})
            info.put(field[0],Arrays.asList((String[])invoke(factory,field[1],new Class<?>[]{String.class},name)));
        List<String> qualifiers=new ArrayList<>();
        try{for(Object q:(Set<?>)invoke(definition,"getQualifiers",new Class<?>[0])){
            Object value=invoke(q,"getAttribute",new Class<?>[]{String.class},"value");
            qualifiers.add(invoke(q,"getTypeName",new Class<?>[0])+(value==null?"":" = "+value));
        }}catch(NoSuchMethodException|NullPointerException ignored){}
        info.put("qualifiers",qualifiers);
        Object existing=invoke(factory,"getSingleton",new Class<?>[]{String.class},name);
        info.put("instantiated",existing!=null);info.put("proxy",existing==null?"not instantiated":Proxy.isProxyClass(existing.getClass())?"JDK":existing.getClass().getName().contains("$$SpringCGLIB")?"CGLIB":"none");
        if(existing!=null)try{
            Class<?> aop=Class.forName("org.springframework.aop.support.AopUtils",false,AppClassPath.loader(context));
            info.put("targetClass",((Class<?>)aop.getMethod("getTargetClass",Object.class).invoke(null,existing)).getName());
        }catch(ReflectiveOperationException ignored){}
        List<Map<String,Object>> methods=new ArrayList<>();
        for(Method method:Arrays.stream(type.getMethods()).filter(m->m.getDeclaringClass()!=Object.class&&!m.isSynthetic()&&!Modifier.isStatic(m.getModifiers()))
                .sorted(Comparator.comparing(Method::getName).thenComparing(BeanExplorer::descriptor)).limit(256).toList()){
            List<String> types;
            try{types=InvocationPlan.types(method);}catch(IllegalArgumentException failure){types=List.of();}
            Map<String,Object> row=new LinkedHashMap<>();row.put("name",method.getName());row.put("descriptor",descriptor(method));row.put("signature",method.toGenericString());
            row.put("parameters",types);row.put("supported",types.size()==method.getParameterCount());methods.add(row);
        }
        info.put("methods",methods);info.put("note","Dependency edges describe dependencies resolved so far; lazy beans are not initialized by this view.");
        return Map.of("bean-json",CaseJson.write(info));
    }
    static String descriptor(Method method){return java.lang.invoke.MethodType.methodType(method.getReturnType(),method.getParameterTypes()).toMethodDescriptorString();}
    static Method method(Class<?> type,String name,String descriptor){
        return Arrays.stream(type.getMethods()).filter(m->m.getName().equals(name)&&descriptor(m).equals(descriptor)&&!m.isSynthetic()).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Select an exact public method signature"));
    }
    static Map<String,Object> prepare(Object context,Map<String,String> request) throws Exception {
        String bean=request.get("bean");Class<?> type=type(context,bean);Method method=method(type,request.get("method"),request.get("descriptor"));
        type=invocationType(type,method);method=type.getMethod(method.getName(),method.getParameterTypes());
        List<String> types=InvocationPlan.types(method);
        Object parsed=CaseJson.parse(request.getOrDefault("inputs-json","[]"));
        if(!(parsed instanceof List<?> inputs)||inputs.size()!=types.size()||inputs.stream().anyMatch(i->!(i instanceof String)))throw new IllegalArgumentException("Choose one DATA name per parameter");
        StringBuilder code=new StringBuilder();List<String> args=new ArrayList<>();
        for(int i=0;i<inputs.size();i++){
            String name=(String)inputs.get(i);SnapshotManager.requireData(name);
            String variable="callArg"+i;
            code.append(types.get(i)).append(' ').append(variable).append(" = (").append(types.get(i)).append(") com.baader.devrt.SnapshotManager.loadTyped(")
                .append(CaseJson.write(name)).append(", ").append(CaseJson.write(InvocationPlan.runtimeType(method.getGenericParameterTypes()[i]))).append(");\n");args.add(variable);
        }
        code.append("ctx.getBean(").append(CaseJson.write(bean)).append(", ").append(InvocationPlan.sourceType(type)).append(".class).")
            .append(method.getName()).append('(').append(String.join(", ",args)).append(");");
        return Map.of("code",code.toString(),"value","Prepared only; run explicitly to deserialize DATA and call the bean");
    }
    static Map<String,Object> compatible(Object context,Map<String,String> request) throws Exception {
        Method method=method(type(context,request.get("bean")),request.get("method"),request.get("descriptor"));
        int parameter=Integer.parseInt(request.getOrDefault("parameter","0"));
        if(parameter<0||parameter>=method.getParameterCount())throw new IllegalArgumentException("Invalid parameter index");
        String exact=InvocationPlan.types(method).get(parameter);List<Map<String,String>> found=new ArrayList<>();
        for(String entry:SnapshotManager.list()){
            String[] fields=entry.split("\t");if(fields.length<3||!fields[2].equals("DATA"))continue;
            String declared=fields[1];
            if(declared.replace(" ","").replace('$','.').equals(exact.replace(" ","")))found.add(Map.of("name",fields[0],"type",declared,"match","declared type"));
            else if(!exact.contains("<")&&!declared.contains("<"))try{
                Class<?> candidate=Class.forName(declared,false,AppClassPath.loader(context));
                if(method.getParameterTypes()[parameter].isAssignableFrom(candidate))found.add(Map.of("name",fields[0],"type",declared,"match","assignable raw type; validate before use"));
            }catch(ClassNotFoundException ignored){}
        }
        return Map.of("type",exact,"snapshots-json",CaseJson.write(found));
    }
}
