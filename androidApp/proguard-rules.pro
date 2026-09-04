-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class **$$serializer { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { public <init>(); }
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
-dontwarn lombok.NonNull
