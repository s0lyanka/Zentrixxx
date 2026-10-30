-keep class org.telegram.tgnet.** { *; }

# LuaJ and Zentrix Proguard Rules
-dontwarn javax.script.**
-dontwarn org.luaj.vm2.**
-dontwarn org.apache.bcel.**
-keep class org.luaj.vm2.** { *; }
-keep class org.telegram.messenger.zentrix.** { *; }
