# JNI symbol names reference this class. Keep them stable when enabling R8.
-keep class com.example.disksize.scan.NativeBridge { *; }
-keep interface com.example.disksize.scan.NativeScanObserver { *; }
-keepclassmembers class * implements com.example.disksize.scan.NativeScanObserver {
    public void onProgress(long, long, long, long);
}
