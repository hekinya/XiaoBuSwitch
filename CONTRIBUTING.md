# 贡献指南

1. Fork 仓库，从 `main` 建立分支，分支名用简短的连字符命名，例如 `fix-breeno-guard`。
2. 修改后运行测试、覆盖率检查、Android lint 与构建：

   ```bash
   ./gradlew test jacocoTestCoverageVerification :app:lintDebug :app:assembleDebug
   ```

   Windows 可使用 `.\gradlew.bat`。构建环境见 [README](README.md#从源码构建)。
3. 提交使用 `type(scope): summary`，例如 `fix(coloros): restore provider selection`。Pull Request 目标分支为 `main`，描述改动及验证结果。

报告问题时，请附上小布 Switch、小布助手、ColorOS / Android 和 LSPosed 的版本、复现步骤与脱敏日志。不要提交设备 APK、反编译导出、账号数据、API Key 或私人日志。

项目源码采用 [GPL-3.0-only](LICENSE)。
