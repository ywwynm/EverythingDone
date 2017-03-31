# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in D:\Software\Android\sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

-optimizationpasses 5
#混淆时不会产生形形色色的类名
-dontusemixedcaseclassnames
#保留行号
-renamesourcefileattribute Secret
-keepattributes SourceFile,LineNumberTable
#指定不去忽略非公共的类库
-dontskipnonpubliclibraryclasses
#不进行预校验
-dontpreverify
-verbose
#优化操作
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*


#-------------------------------------通用混淆配置 start ---------------------------------------

# 对于继承Android的四大组件等系统类，保持原样
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference
-keep public class com.android.vending.licensing.ILicensingService
-keep class android.support.**{*;}
-keep class android.support.v4.** { *; }
-keep interface android.support.v4.app.** { *; }
-keep public class * extends android.support.v4.**

-dontwarn android.support.v4.**
-dontwarn com.android.support.**
-dontwarn android.support.**

# 保对所有类的native方法名不进行混淆
-keepclasseswithmembernames class *{
    native <methods>;
}
# 对所有类的初始化方法的方法名不进行混淆
-keepclasseswithmembers class *{
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class *{
    public <init>(android.content.Context, android.util.AttributeSet,int);
}
# 保护继承至View对象中的set/get方法
-keepclassmembers public class * extends android.view.View {
   void set*(***);
   *** get*();
}
# 对枚举类型enum的所有类的以下指定方法的方法名不进行混淆
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# 对实现了Parcelable接口的所有类的类名不进行混淆，对其成员变量为Parcelable$Creator类型的成员变量的变量名不进行混淆
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

#Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public enum com.bumptech.glide.load.resource.bitmap.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}

#JodaTime
-dontwarn org.joda.convert.**
-dontwarn org.joda.time.**
-keep class org.joda.time.** { *; }
-keep interface org.joda.time.** { *; }

#-keep class com.ywwynm.everythingdone.model.Thing { *; }

#-keep class com.ywwynm.everythingdone.utils.DisplayUtil
#-keep class com.ywwynm.everythingdone.utils.EdgeEffectUtil