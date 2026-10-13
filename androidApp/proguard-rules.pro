# ML Kit 从 manifest 反射创建注册类；依赖规则只保留类，R8 strict full mode
# 仍会删除无参构造函数，导致 BarcodeScanning.getClient() 取不到扫描组件。
-keep class com.google.mlkit.** implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
