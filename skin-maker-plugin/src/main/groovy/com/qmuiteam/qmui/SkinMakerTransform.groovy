/*
 * Tencent is pleased to support the open source community by making QMUI_Android available.
 *
 * Copyright (C) 2017-2018 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the MIT License (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/MIT
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.qmuiteam.qmui

import com.android.build.api.transform.*
import com.android.build.gradle.internal.pipeline.TransformManager
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import javassist.ClassPool
import javassist.CtClass
import javassist.CtMethod
import javassist.CtNewMethod
import javassist.NotFoundException
import jdk.internal.util.xml.impl.Pair
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel

class SkinMakerTransform extends Transform {

    private Project mProject
    private SkinMakerPlugin.SkinMaker mSkinMaker

    SkinMakerTransform(Project project, SkinMakerPlugin.SkinMaker skinMaker) {
        mProject = project
        mSkinMaker = skinMaker
    }


    @Override
    String getName() {
        return "skin-maker"
    }

    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS
    }

    @Override
    Set<? super QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    @Override
    boolean isIncremental() {
        return false
    }

    @Override
    void transform(TransformInvocation transformInvocation) throws TransformException, InterruptedException, IOException {
        File sourceFile = mSkinMaker.file
        if (sourceFile == null || !sourceFile.exists()) {
            return
        }
        mProject.logger.log(LogLevel.INFO, "skin code source: " + sourceFile.path)

        def injectCode = new InjectCode()
        injectCode.parseFile(sourceFile)

        def androidJar = mProject.android.bootClasspath[0].toString()

        def externalDepsJars = new ArrayList<File>()
        def externalDepsDirs = new ArrayList<File>()

        transformInvocation.referencedInputs.each { transformInput ->
            transformInput.jarInputs.each { jar ->
                externalDepsJars.add(jar.file)
            }
            transformInput.directoryInputs.each { directoryInput ->
                externalDepsDirs.add(directoryInput.file)
            }
        }

        transformInvocation.outputProvider.deleteAll()


        def inputJars = new ArrayList<File>()
        transformInvocation.inputs.each { input ->
            input.jarInputs.each {
                inputJars.add(it.file)
            }
        }

        transformInvocation.inputs.each { input ->
            input.directoryInputs.each { directoryInput ->
                def baseDir = directoryInput.file
                ClassPool pool = new ClassPool()
                pool.appendSystemPath()
                pool.appendClassPath(androidJar)
                externalDepsJars.each { pool.appendClassPath(it.absolutePath) }
                externalDepsDirs.each { pool.appendClassPath(it.absolutePath) }
                inputJars.each { pool.appendClassPath(it.absolutePath) }
                pool.appendClassPath(baseDir.absolutePath)


                handleDirectionInput(injectCode, directoryInput, pool){ className, methodName, scope, ctClass ->
                    if(!scope.skin.isEmpty() || !scope.id.isEmpty()){
                        def sb = new StringBuilder()
                        sb.append("public void skinMaker")
                        sb.append(methodName)
                        sb.append("(){")
                        scope.skin.each {codeInfo ->
                            sb.append(codeInfo.fieldName)
                            sb.append(".setTag(com.qmuiteam.qmui.R.id.qmui_skin_value, \"")
                            sb.append(codeInfo.code)
                            sb.append("\");\n")
                        }
                        scope.id.each {codeInfo ->
                            def idNameIndex = codeInfo.fieldName.lastIndexOf(".")
                            def id = codeInfo.fieldName.substring(idNameIndex + 1)
                            def atIndex = codeInfo.code.lastIndexOf("@")
                            def context = codeInfo.code.substring(atIndex + 1)
                            sb.append("android.view.View ")
                            sb.append(id)
                            sb.append(" = ")
                            sb.append(context)
                            sb.append(".findViewById(")
                            sb.append(codeInfo.fieldName)
                            sb.append(");\n")
                            sb.append("if(")
                            sb.append(id)
                            sb.append(" != null){")
                            sb.append(id)
                            sb.append(".setTag(com.qmuiteam.qmui.R.id.qmui_skin_value, \"")
                            sb.append(codeInfo.code.substring(0, atIndex))
                            sb.append("\");}\n")
                        }
                        sb.append("}")
                        CtMethod newMethod = CtMethod.make(sb.toString(), ctClass)
                        ctClass.addMethod(newMethod)
                        scope.methodCreated = true
                    }
                }


                handleDirectionInput(injectCode, directoryInput, pool){ className, methodName, scope, ctClass ->
                    if(!scope.method.isEmpty()){
                        if(scope.methodCreated){
                            CtMethod ctMethod = ctClass.getDeclaredMethod("skinMaker" + methodName)
                            def sb = new StringBuilder()
                            scope.method.each {codeInfo ->
                                sb.append(codeInfo.fieldName)
                                sb.append(".")
                                sb.append("skinMaker")
                                sb.append(codeInfo.code)
                                sb.append("();\n")
                            }
                            ctMethod.insertAfter(sb.toString())
                        }else{
                            def sb = new StringBuilder()
                            sb.append("public void skinMaker")
                            sb.append(methodName)
                            sb.append("(){")
                            scope.method.each {codeInfo ->
                                sb.append(codeInfo.fieldName)
                                sb.append(".")
                                sb.append("skinMaker")
                                sb.append(codeInfo.code)
                                sb.append("();\n")
                            }
                            sb.append("}")
                            CtMethod newMethod = CtMethod.make(sb.toString(), ctClass)
                            ctClass.addMethod(newMethod)
                            scope.methodCreated = true
                        }
                    }

                    if (className.endsWith("Fragment")) {
                        CtMethod ctMethod
                        try{
                            ctMethod = ctClass.getDeclaredMethod("onViewCreated", pool.get("android.view.View"))
                        }catch(NotFoundException ignore) {
                            ctMethod = CtNewMethod.make(
                                    "protected void onViewCreated(android.view.View rootView) { super.onViewCreated(rootView);}", ctClass)
                            ctClass.addMethod(ctMethod)
                        }
                        ctMethod.insertAfter("skinMaker" + className.split("\\.").last() + "();")
                    } else if (className.endsWith("Activity")) {
                        CtMethod ctMethod
                        try{
                            ctMethod = ctClass.getMethod("onCreate", pool.get("android.os.Bundle"))
                        }catch(NotFoundException ignore) {
                            ctMethod = CtNewMethod.make(
                                    "protected void onCreate(android.os.Bundle savedInstanceState){super.onCreate(savedInstanceState);}",
                                    ctClass)
                            ctClass.addMethod(ctMethod)
                        }
                        ctMethod.insertAfter("skinMaker" + className.split("\\.").last() + "();")
                    }

                }

                def dest = transformInvocation.outputProvider.getContentLocation(
                        directoryInput.name,
                        directoryInput.contentTypes,
                        directoryInput.scopes,
                        Format.DIRECTORY)

                FileUtils.copyDirectory(directoryInput.file, dest)
            }

            //遍历jar文件 对jar不操作，但是要输出到out路径
            input.jarInputs.each { jarInput ->
                def jarName = jarInput.name
                def md5Name = DigestUtils.md5Hex(jarInput.file.getAbsolutePath())
                if (jarName.endsWith(".jar")) {
                    jarName = jarName.substring(0, jarName.length() - 4)
                }
                def dest = transformInvocation.outputProvider.getContentLocation(
                        jarName + md5Name,
                        jarInput.contentTypes,
                        jarInput.scopes,
                        Format.JAR)
                FileUtils.copyFile(jarInput.file, dest)
            }
        }
    }

    void handleDirectionInput(InjectCode injectCode, DirectoryInput directoryInput, ClassPool pool,
                              @ClosureParams(value = SimpleType.class, options = ["java.lang.String", "java.lang.String","com.qmuiteam.qmui.CodeInScope", "javassist.CtClass"]) Closure closure){
        directoryInput.file.eachFileRecurse { file ->
            String filePath = file.absolutePath
            if (filePath.endsWith(".class")) {
                def className = filePath.substring(directoryInput.file.absolutePath.length() + 1, filePath.length() - 6)
                        .replace('/', '.')
                def codeMapForClass = injectCode.getCodeMapForClass(className)
                if (codeMapForClass != null && !codeMapForClass.isEmpty()) {
                    CtClass ctClass = pool.getCtClass(className)
                    if (ctClass.isFrozen()) {
                        ctClass.defrost()
                    }
                    codeMapForClass.keySet().each { methodName ->
                        def scope = codeMapForClass.get(methodName)
                        closure.call(className, methodName, scope, ctClass)
                    }
                    ctClass.writeFile(directoryInput.file.absolutePath)
                }
            }
        }
    }


    class InjectCode {
        private HashMap<String, HashMap<String, CodeInScope>> mCodeMap = new HashMap<>()

        private String mCurrentClassName = null
        private HashMap<String, CodeInScope> mCurrentCodes = null

        void parseFile(File file) {
            file.newReader().lines().each { text ->
                if (text != null) {
                    text = text.trim()
                    if (!text.isBlank()) {
                        if (mCurrentClassName == null) {
                            mCurrentClassName = text
                            mCurrentCodes = new HashMap<String, CodeInScope>()
                            mCodeMap.put(mCurrentClassName, mCurrentCodes)
                        } else if (text != ";") {
                            int start = 0
                            int split = text.indexOf(",", start)
                            String key = text.substring(start, split)
                            CodeInScope scope = mCurrentCodes.get(key)
                            if (scope == null) {
                                scope = new CodeInScope()
                                mCurrentCodes.put(key, scope)
                            }

                            // type
                            start = split + 1
                            split = text.indexOf(",", start)
                            def type = text.substring(start, split)

                            def codeInfo = new CodeInfo()

                            // field name
                            start = split + 1
                            split = text.indexOf(",", start)
                            codeInfo.fieldName = text.substring(start, split)

                            // code
                            codeInfo.code = text.substring(split + 1)
                            if(type == "ref"){
                                scope.skin.add(codeInfo)
                            }else if(type == "method"){
                                scope.method.add(codeInfo)
                            }else if(type == "id"){
                                scope.id.add(codeInfo)
                            }
                        } else {
                            mCurrentClassName = null
                            mCurrentCodes = null
                        }
                    }
                }
            }
        }

        HashMap<String, CodeInScope> getCodeMapForClass(String className) {
            return mCodeMap.get(className)
        }
    }
}

class CodeInfo {
    String fieldName
    String code
}

class CodeInScope {
    ArrayList<CodeInfo> skin = new ArrayList<>()
    ArrayList<CodeInfo> method = new ArrayList<>()
    ArrayList<CodeInfo> id = new ArrayList<>()
    boolean methodCreated = false
}