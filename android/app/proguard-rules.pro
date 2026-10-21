# Release builds use R8 optimization and obfuscation. Debug builds stay readable.
# AGP and the libraries provide their consumer rules, including serialization.
# Keep Android entry points, not every application class (which defeats obfuscation).
-keep,allowoptimization class com.homedesk.chat.MainActivity { public <init>(); }
-keep,allowoptimization class com.homedesk.chat.DeskApp { public <init>(); }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

