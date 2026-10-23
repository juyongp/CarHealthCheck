# 릴리스 빌드에서 축소(minify)를 켤 경우를 대비한 규칙이에요.
# WebView 자바스크립트 브리지
-keepclassmembers class com.carhealth.checkup.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
# Vehicle SDK는 리플렉션으로 호출하므로 SDK 클래스를 유지해요.
-keep class ai.pleos.playground.vehicle.** { *; }
-keep class kotlin.Metadata { *; }
