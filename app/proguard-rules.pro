# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /home/marcel/Android/Sdk/tools/proguard/proguard-android.txt
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

#Line numbers
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

#NetGuard
-keepnames class com.zhengde163.netguard.** { *; }

#JNI
-keepclasseswithmembernames class * {
    native <methods>;
}

#JNI callbacks
-keep class com.zhengde163.netguard.Allowed { *; }
-keep class com.zhengde163.netguard.Packet { *; }
-keep class com.zhengde163.netguard.ResourceRecord { *; }
-keep class com.zhengde163.netguard.Usage { *; }
-keep class com.zhengde163.netguard.ServiceSinkhole {
    void nativeExit(java.lang.String);
    void nativeError(int, java.lang.String);
    void logPacket(com.zhengde163.netguard.Packet);
    void dnsResolved(com.zhengde163.netguard.ResourceRecord);
    boolean isDomainBlocked(java.lang.String);
    com.zhengde163.netguard.Allowed isAddressAllowed(com.zhengde163.netguard.Packet);
    void accountUsage(com.zhengde163.netguard.Usage);
}

#Support library
-keep class android.support.v7.widget.** { *; }
-dontwarn android.support.v4.**

#Picasso
-dontwarn com.squareup.okhttp.**
