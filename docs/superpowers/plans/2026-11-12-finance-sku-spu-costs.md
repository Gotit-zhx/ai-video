# 订单成本 SKU / SPU 匹配

用户规则：订单成本表的商品 SKU 右侧增加平台 SPU；导入成本表的型号先按商品 SKU 匹配，失败后按平台 SPU 匹配，两者均不能唯一匹配时提示人工确认。

## 1. 自动成本匹配

修改 `FinanceCostWorkspace.java`、`FinanceOrderCosts.java`。共享入口：

```java
static CostCodeMatch matchProduct(String sellerCode,String productId,
    Set<String> codes,Map<String,Set<String>> normalized)
```

先调用既有 `matchSku`，保留独立组合型号及 SKU 数量后缀；未命中再按 `matchingCostCode(productId,...)` 查 SPU，返回 `SPU_CODE`，不从未命中的 SKU 推测 SPU 换算倍数。可匹配集合限定当前分组、成本仓；其他仓的型号只用于缺仓说明。统一识别自动关系的方法，已有人工关系仍优先。未命中保留未知成本并提示人工确认；已命中型号的缺价、零价和数量错误继续按原规则处理。

先添加计算回归并观察失败：双命中 SKU 优先、SKU 空白/未知时 SPU 成功、超长 ID 精度、平台 SKU ID 不冒充 SPU、成本仓隔离、自动 SPU 关系不会锁住后续 SKU 匹配、SKU 零价不会偷换 SPU 价格。然后实现，再验证已有人工、GIFT、取消、历史价格规则。自动关联接口逐仓使用同一匹配入口；更新计算源哈希版本，使后续同步及既有重算入口识别新算法，不清空队列或在查询时执行全量重算。

## 2. 表格与关联提示

修改 `FinanceFields.java`、`frontend/finance-costs.js`。ORDER 输出 `product_spu_values: List<String>`，从原订单 `item_id/product_id` 提取，去空、去重且保留字符串精度。新字段不替代原商家 SKU。

订单成本表头插入 `平台 SPU`，位于 `商品 SKU` 之后，单元格按文本展示多个 SPU，缺失显示 `—`。SKU 关联预览与自动关联采用同样 SKU → SPU 顺序；命中显示自动匹配来源，双未命中才显示核对关联。已有人工关联、移除成本提示继续保留。

先添加 `FinanceProductIdentityTest` 及 `test-finance-cost-spu-ui.mjs` 的失败回归：正常 SKU 行也有 SPU、多个 SPU 去重、长 ID 不失真、表头顺序、SKU 优先、SPU 兜底、双未命中、人工关系保护。再实现和执行相关财务页面回归。

## 3. 集成与交付

新增隔离 MySQL 集成用例：导入型号混合的成本 CSV，保存两平台订单，通过现有重算队列得到真实持久化成本，再查询订单成本 API 验证 SPU 与金额；检查自动关联方法及公司/分组/仓隔离。测试只连接可销毁的测试库。

更新 `docs/business/finance/costs.md`，说明型号匹配顺序、人工关系优先、SPU 是商品 ID，以及既有历史成本依靠导入/同步/重算队列更新。独立审查后在 `feature/finance-cost-sku-spu-fallback` 提交本任务文件并创建面向 `develop` 的 PR，保持共享服务器运行边界。
