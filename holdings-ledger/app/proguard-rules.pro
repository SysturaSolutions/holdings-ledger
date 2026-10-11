# Keep the JavaScript bridge methods callable from the WebView
-keepclassmembers class com.holdingsledger.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
