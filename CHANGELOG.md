# 更新日志

## 1.0.1

状态：待发布。

- 修复 JVM `sourcesElements` 未正规化导致 JitPack 将源码变体指向主 JAR、与声明 size/hash 冲突的问题；保留 sources JAR 文件及 API/runtime 变体。
- 扩展现有发布元数据回归，核对精确变体删除及 API/runtime、自定义变体保留。
- 将组件和独立消费者默认版本更新为 `1.0.1`，同步安装示例与验证边界；Kotlin API 和运行时行为不变。
