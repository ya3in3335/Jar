# Keep model/DTO class names that are matched by reflection or serialised.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# OkHttp ships optional dependencies it only uses when present.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Hilt/Dagger generate code that R8 can shrink safely; keep entry points.
-keep class com.jarvis.ai.JarvisApp { *; }
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# Enum valueOf/entries are used via reflection by Compose tooling.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Our own state and routing types are referenced by name in logs/tests.
-keep class com.jarvis.ai.core.agent.AssistantState { *; }
