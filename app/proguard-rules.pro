# Keep JNI bridge classes/methods referenced from native code.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.mastar.editor.engine.gl.NativeBridge { *; }
