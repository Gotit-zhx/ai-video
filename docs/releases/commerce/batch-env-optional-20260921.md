# 裂变批次店铺人工环境标记改为可选

> 历史发布记录；镜像、测试与“当前/最新”均指记录当时。 当前入口见[业务说明](../../business/README.md)与[运维指南](../../guides/operations.md)。

截图报错来自 CommerceBatchService：店铺已授权但缺少 ShopOperatingSettings 或 environment=UNKNOWN 时，创建被拒绝。后续 CommerceBatchPreflightService 也有同类重复阻断。

两处均取消人工环境标记必填。未配置运营设置时沿用现有店铺设置页面的每日 50 条默认额度，并与批次额度取较小值；已有设置额度保持不变。明确 TEST/LIVE 的混用校验保留。没有写入/推断店铺环境，也未变更平台凭据、API 地址或发布权限。

验证：CommerceBatchServiceTest 24 项、CommerceBatchPreflightServiceTest 16 项全部通过。发布前将修改前源码对当前生产依赖编译，用 javap -c -p 比对线上两个类，完全相同后才将两个修复类及内部类覆盖到当前生产 jar。发布脚本在活动任务为零时重启应用并检查 health UP，失败自动回滚。

发布镜像：ai-commerce-platform:batch-env-optional-20260921。
原镜像：ai-commerce-platform:image-download-retry-20260921。
