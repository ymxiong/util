package cc.eamon.open.permission.annotation;

import cc.eamon.open.permission.PermissionInit;
import cc.eamon.open.permission.mvc.PermissionChecker;
import cc.eamon.open.status.StatusException;
import com.squareup.javapoet.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import javax.lang.model.element.Element;
import javax.lang.model.util.Types;
import java.util.*;

/**
 * Created by Eamon on 2018/9/30.
 */
@SupportedAnnotationTypes(
        {
                "cc.eamon.open.permission.annotation.PermissionInit"
        }
)
public class PermissionProcessor extends AbstractProcessor {

    private Types typeUtils;
    private Elements elementUtils;
    private Filer filer;
    private Messager messager;

    public PermissionProcessor() {}


    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        typeUtils = processingEnv.getTypeUtils();
        elementUtils = processingEnv.getElementUtils();
        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();
    }


    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> annotations = new LinkedHashSet<>();
        annotations.add(Permission.class.getCanonicalName());
        return annotations;
    }



    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        try{
            HashMap<String, FieldSpec> fieldHashMap = new HashMap<>();
            HashMap<String, String> methodNameHashMap = new HashMap<>();
            ClassName string = ClassName.get("java.lang", "String");


            for (Element elem : roundEnv.getElementsAnnotatedWith(Permission.class)) {
                if (elem.getKind() != ElementKind.CLASS){
                    return true;
                }

                Permission type = elem.getAnnotation(Permission.class);
                String typeName = type.value();

                for(Element elemMethod: elem.getEnclosedElements()){
                    if (elemMethod.getKind() == ElementKind.METHOD){
                        PermissionLimit limit = elemMethod.getAnnotation(PermissionLimit.class);
                        String methodName;
                        String methodDetail;

                        if ((limit != null) && !limit.name().equals("")){
                            methodName = typeName.toUpperCase() + "_" + limit.name().toUpperCase();
                            methodDetail = typeName.toLowerCase() + "_" + limit.name();
                        }else {
                            methodName = typeName.toUpperCase() + "_" + elemMethod.getSimpleName().toString().toUpperCase();
                            methodDetail = typeName.toLowerCase() + "_" + elemMethod.getSimpleName().toString();
                        }

                        FieldSpec.Builder fieldBuilder = FieldSpec.builder(
                                string,
                                methodName,
                                Modifier.PUBLIC,
                                Modifier.STATIC,
                                Modifier.FINAL)
                                .initializer("$S", methodDetail);

                        if (fieldHashMap.get(methodName)!=null){
                            throw new ProcessingException(elem, "PermissionInit identifier can not be same @%s", Permission.class.getSimpleName());
                        }
                        methodNameHashMap.put(methodName, methodDetail);
                        fieldHashMap.put(methodName, fieldBuilder.build());
                    }
                }
            }

            if (fieldHashMap.size()>0){
                String packageNameRoot = "cc.eamon.open.permission";
                String packageNameMvc = "cc.eamon.open.permission.mvc";

                String classNameValue = "PermissionValue";
                String classNameRole = "PermissionRole";
                String classNameChecker = "DefaultChecker";

                // 新建角色常量类
                TypeSpec.Builder typeSpecRole = TypeSpec.classBuilder(classNameRole)
                        .addModifiers(Modifier.PUBLIC);

                for (String key:PermissionInit.getNameToPermissionMap().keySet()){
                    FieldSpec.Builder fieldBuilder = FieldSpec.builder(
                            string,
                            key,
                            Modifier.PUBLIC,
                            Modifier.STATIC,
                            Modifier.FINAL)
                            .initializer("$S", key.toLowerCase());

                    typeSpecRole.addField(fieldBuilder.build());
                }
                //写入角色常量类
                JavaFile.builder(packageNameRoot, typeSpecRole.build()).build().writeTo(filer);


                ClassName checker = ClassName.get("cc.eamon.open.permission.mvc", "PermissionChecker");
                ClassName httpServletRequest = ClassName.get("javax.servlet.http", "HttpServletRequest");
                ClassName httpServletResponse = ClassName.get("javax.servlet.http", "HttpServletResponse");
                ClassName permissionValue = ClassName.get("cc.eamon.open.permission", "PermissionValue");

                // 新建Value类
                TypeSpec.Builder typeSpecValue = TypeSpec.classBuilder(classNameValue)
                        .addModifiers(Modifier.PUBLIC);

                HashMap<String, String> methodNames = new HashMap<>();

                for(String key:fieldHashMap.keySet())
                {
                    System.out.println("Key: "+key);
                    //添加Value类的域
                    typeSpecValue.addField(fieldHashMap.get(key));

                    //添加DefaultChecker的方法
                    String mNameSplits[] = methodNameHashMap.get(key).split("_");
                    StringBuilder methodNameBuilder = new StringBuilder("check");
                    //生成方法名
                    for (String mNameSplit:mNameSplits){
                        methodNameBuilder.append(mNameSplit.substring(0, 1).toUpperCase()).append(mNameSplit.substring(1));
                    }
                    String methodName = methodNameBuilder.toString();
                    methodNames.put(key, methodName);
                }
                //写Value类
                JavaFile.builder(packageNameRoot, typeSpecValue.build()).build().writeTo(filer);

                //新建Checker类
                TypeSpec.Builder typeSpecMvc = TypeSpec.classBuilder(classNameChecker)
                        .addSuperinterface(checker)
                        .addModifiers(Modifier.PUBLIC)
                        .addModifiers(Modifier.ABSTRACT);

                //添加checkRole函数至DefaultChecker
                MethodSpec.Builder checkRole = MethodSpec.methodBuilder("checkRole")
                        .addModifiers(Modifier.PUBLIC)
                        .addModifiers(Modifier.ABSTRACT)
                        .addParameter(httpServletRequest,"request")
                        .addParameter(httpServletResponse, "response")
                        .addParameter(string, "roleLimit")
                        .addException(StatusException.class)
                        .returns(TypeName.BOOLEAN);
                typeSpecMvc.addMethod(checkRole.build());

                //添加checkMethod至DefaultChecker
                MethodSpec.Builder checkMethod = MethodSpec.methodBuilder("check")
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(httpServletRequest,"request")
                        .addParameter(httpServletResponse, "response")
                        .addParameter(string,"methodName")
                        .addParameter(string, "roleLimit")
                        .addAnnotation(Override.class)
                        .addException(StatusException.class)
                        .returns(TypeName.BOOLEAN);

                checkMethod.beginControlFlow("if (!checkRole(request, response, roleLimit))");
                checkMethod.addStatement("return false");
                checkMethod.endControlFlow();

                checkMethod.beginControlFlow("switch (methodName)");
                //添加具体方法
                for(String key:methodNames.keySet()){
                    //添加方法
                    MethodSpec.Builder checkMethodDetail = MethodSpec.methodBuilder(methodNames.get(key))
                            .addModifiers(Modifier.PUBLIC)
                            .addParameter(httpServletRequest,"request")
                            .addParameter(httpServletResponse, "response")
                            .addException(StatusException.class)
                            .returns(TypeName.BOOLEAN);
                    checkMethodDetail.addStatement("return true");
                    typeSpecMvc.addMethod(checkMethodDetail.build());

                    checkMethod.addStatement("case $T."+ key +": return " + methodNames.get(key) + "(request, response)", permissionValue);
                }
                checkMethod.addStatement("default: break");
                checkMethod.endControlFlow();
                checkMethod.addStatement("return true");

                typeSpecMvc.addMethod(checkMethod.build());
                JavaFile.builder(packageNameMvc, typeSpecMvc.build()).build().writeTo(filer);
            }

        }catch (Exception e){
            System.out.println(e.toString());
        }

        return true;
    }


}