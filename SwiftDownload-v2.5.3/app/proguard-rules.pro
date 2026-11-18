# jlibtorrent: keep SWIG-generated classes and native binding
-keep class com.frostwire.jlibtorrent.** { *; }
-dontwarn com.frostwire.jlibtorrent.**

# Keep entities stored via reflection-free SQLite (paranoia for enum names)
-keepclassmembers enum com.swift.download.** { *; }
