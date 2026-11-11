# 历史发布工作流归档

归档日期：2026-11-11。本目录保存原 `candidate.yml`、`ci.yml`、`deploy.yml`、`release-ready.yml`，用于追溯旧 Candidate、正式标签、镜像构建与发布证明。这些文件位于 `docs/history/`，不是 GitHub Actions 当前执行入口；不要复制回 `.github/workflows/` 来恢复日常发布。

当前主应用与前端已切换为源码直接运行，真实验收见[源码运行现场状态](../../guides/source-runtime-status.md)。协作检查由 `.github/workflows/development-checks.yml` 承担；旧 CD/release_flow 已关闭，`main-tree`、旧 release/hotfix 分支、标签与镜像继续保留。其他独立服务和桌面分发遵守各自运行边界。

旧工作流和脚本仅用于明确的历史审计或恢复任务。执行前必须重新核对当前数据库结构、配置、凭据、挂载、业务卷和维护状态；应用恢复不等于数据回滚。运行未提交源码不要求 Candidate、版本标签、JAR/OCI 或静态发布包。
