# libtdjni.so constructs TdApi objects and calls Client callbacks by name over JNI, so R8 must
# keep every class, constructor and field in the package exactly as generated.
-keep class org.drinkless.tdlib.** { *; }
