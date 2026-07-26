# ============================================================================
# ProGuard / R8 规则
#
# 原则：所有依赖库（AndroidX、Material、Room、coroutines、Kable 等）都自带
# consumer rules，Manifest 声明的组件由 R8 自动保留——不要全量 keep。
# 只保留：可读崩溃栈 + 确有按名访问需求的类。
# 出现运行时问题时按崩溃栈定点补规则，而不是恢复全量 keep。
# ============================================================================

# 崩溃日志可读（保留文件名与行号，混淆映射见 build 产出的 mapping.txt）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Webhook 序列化为 org.json 手写 put/get（无反射，理论上不需要 keep）。
# 保留仅为防御性：防止未来有人改用反射式序列化时与存量磁盘数据静默错位，成本为零。
-keepclassmembers class com.example.heart_rate_monitor_mobile.data.Webhook { <fields>; }
-keepclassmembers enum com.example.heart_rate_monitor_mobile.data.WebhookTrigger { *; }
