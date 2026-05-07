# Add project specific ProGuard rules here.

-optimizationpasses 5
-dontusemixedcaseclassnames
-renamesourcefileattribute Secret
-keepattributes SourceFile,LineNumberTable
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*

#-------------------------------------通用混淆配置 start ---------------------------------------

-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference
-keep public class com.android.vending.licensing.ILicensingService
-keep class androidx.**{*;}
-keep class androidx.core.** { *; }
-keep interface androidx.fragment.app.** { *; }
-keep public class * extends androidx.fragment.**

-dontwarn androidx.core.**
-dontwarn com.google.android.material.**
-dontwarn androidx.**

-keepclasseswithmembernames class *{
    native <methods>;
}
-keepclasseswithmembers class *{
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class *{
    public <init>(android.content.Context, android.util.AttributeSet,int);
}
-keepclassmembers public class * extends android.view.View {
   void set*(***);
   *** get*();
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}
-keepclassmembers class fqcn.of.javascript.interface.for.webview {
public *;
}

-keep class **.R$* {
*;
}
#----------------------------------------通用混淆配置 end ------------------------------------

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder {
    *** rewind();
}

# JodaTime
-dontwarn org.joda.convert.**
-dontwarn org.joda.time.**
-keep class org.joda.time.** { *; }
-keep interface org.joda.time.** { *; }
