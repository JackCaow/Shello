# JSch instantiates negotiated algorithms and authentication methods by class name.
# These rules apply only to the sample; SSH is not a terminal AAR dependency.
-keep class com.jcraft.jsch.** extends com.jcraft.jsch.KeyExchange { *; }
-keep class com.jcraft.jsch.** extends com.jcraft.jsch.UserAuth { *; }
-keep class com.jcraft.jsch.jce.** { *; }
-keep class com.jcraft.jsch.jzlib.** { *; }
