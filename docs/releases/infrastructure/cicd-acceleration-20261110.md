# CI/CD 提效改造验收 — 2026-11-10

更新：2026-11-11。**v7 首发、trusted CI runtime 复用及 v2026.11.11.1 的首个 v2/full、真实 delta 生产发布已核验，必要合回和 CLI journal complete 已确认，V2_BOOTSTRAPPED 已启用。** 本次 delta 节省传输字节，但 CD 仍约12分48秒，旧架构尚未达到极速交付目标；实际分级候选仍须单独验收。以下开关状态是本次核对快照。

## 范围

风险分级、编译输出复用、固定 runtime、前端分层、v1/v2 双读、OCI 增量传输、受信任主机状态、Gitflow 发布命令及恢复 journal。用法见[快速迭代指南](../../guides/cicd-acceleration.md)。本改造不改财务计算、数据库结构、检查点或备份策略。

## 已完成的生产与 Gitflow 证据

| 项目 | 已核实结果 |
| --- | --- |
| 首次正式版本 | [v2026.11.10.7](https://github.com/Gotit-zhx/ai-video/releases/tag/v2026.11.10.7)，schema v1/full；生产提交 `dbf76137103787482ddd6428a2c7a7aac1a3b9e8`，候选源码 `3ac49633a3713a32e38e0391b14754d6dd550b10`，树 `340383eba776155575f019218286e9467c72b2d6`。 |
| 完整验证/候选 | [CI 42837144954/1](https://github.com/Gotit-zhx/ai-video/actions/runs/42837144954)、[Candidate 42838879520/1](https://github.com/Gotit-zhx/ai-video/actions/runs/42838879520) 成功；实际镜像组合、媒体字体、来源证明和不可变候选通过。 |
| 生产门禁 | [PR46](https://github.com/Gotit-zhx/ai-video/pull/46) 使用 [Release readiness 42837049753/1](https://github.com/Gotit-zhx/ai-video/actions/runs/42837049753) 合并；主分支严格保留 `CI required`、`Release ready`，绑定 GitHub Actions app15368，未绕过保护。 |
| 独立控制安装 | control `b05131747fe930a1f405cffcd8875c1691a224c5`，operations `f066004b8a3a81929b626945945a36c574b4253cefa35a0113b2e40b260bc823`；安装回执 `d41eab3f1fbc5c6997f49ff4`，实际 containerd 绑定、v2 接受/拒绝及旧版本回滚预检通过。 |
| 标准部署 | [CD 42840120637/1](https://github.com/Gotit-zhx/ai-video/actions/runs/42840120637) 成功，operation `ea92503d10945dcda97ff53eb6a90b65` 为 `succeeded`；2026-11-10 23:12 上海时间完成，主机操作23秒，业务排空4.486秒、启动1.564秒、后验13.911秒。 |
| 正式附件 | Release 的 `release.json`、`receipt.json` 已下载核对 API SHA-256，分别为 `7577f81081e09614429d89071efc62ba793f3a8b5bb4a9609f24c4bcd1c5566a`、`077fd410d8c53154e58a48a53ce82d814577f69b430cf2d3ec0a45fa12607e57`。 |
| 必要合回 | [PR47](https://github.com/Gotit-zhx/ai-video/pull/47) 已 no-ff 合回 develop `4c995769772a9feeca8aa9c172119b3031425161`，保留正式 main 祖先和最后门禁修复；其树与 v7 相同。 |
| v7 主机观察 | [release-check 42936835195/1](https://github.com/Gotit-zhx/ai-video/actions/runs/42936835195) 及 artifact `13817749988` 已验证来源/摘要；当时观察到 v7、上述 control/operations、schema/transfer `[1,2]`、maintenance=false、operations=[]。五分钟有效观察须在下一操作前刷新。 |

安装前现场记录可用空间 417,284,935,680 bytes；该数值是历史快照。执行继续保留 **10 GiB安全余量及实际峰值暂存预算**、业务卷、备份和合格回滚版本。安装未改变9个容器、配置、schema或备份策略；.5/.4/.3当时均通过兼容回滚预检，预检不等于实际回滚演练。

## 首个 v2/full 与真实 delta 生产回执

2026-11-11 对 [v2026.11.11.1](https://github.com/Gotit-zhx/ai-video/releases/tag/v2026.11.11.1) 独立下载正式附件和指定 CD 的 `deployment-receipt`，并只读核对主机 `status`、`receipt` 及旧版兼容元数据。本次发布由成本修复会话执行；此轮核验没有部署、调整配置或修改开关。

| 项目 | 已核实结果 |
| --- | --- |
| 源码与正式提交 | 候选源码 `53d96850713202d95a1331c56f4d09d40cb2308f`，树 `6b178fe8fe2210e439a1a6561379d66295dae40c`；[PR48](https://github.com/Gotit-zhx/ai-video/pull/48) 已合入生产分支 `main-tree`，merge `80e8a118d348d9f9bab8997a64e02b582f383100`。 |
| 完整验证与候选 | [trusted CI 42938677039/1](https://github.com/Gotit-zhx/ai-video/actions/runs/42938677039)、[Candidate 42939333617/1](https://github.com/Gotit-zhx/ai-video/actions/runs/42939333617) 成功；正式 manifest 为 `schemaVersion=2`、`validation.profile=full`，六类 requiredSuites、`unexpectedSkips=0`、containerSmoke=passed。`full` 指验证覆盖，允许同时采用 delta 传输。 |
| 正式附件摘要 | `release.json` SHA-256 `9d68c69e72cc4fc711ff39e7bb8ed05a6e444723b5cf4e9d6e5c5415276aa12f`，`receipt.json` SHA-256 `afa690ca5d21893d31c6ea028984b35e7666d1a3190cc3d2317500efcfef96a7`，均与 Release API 摘要一致；CD 内 manifest/receipt 与正式附件逐字节相同，主机小回执内容相同。 |
| 接受与耗时 | [CD 42940211574/1](https://github.com/Gotit-zhx/ai-video/actions/runs/42940211574) 成功；09:36:44触发至09:49:32最后job结束共768秒。operation `62e02fc79a84d021193f020e82abe474` 为 succeeded，09:48:25–09:49:09主机操作44秒；业务排空26.131秒、启动1.293秒、后验13.967秒。以上均为上海时间。 |
| 实际传输 | `transferMode=delta`，`logicalBytes=489,191,454`，`cachedBlobBytes=390,325,271`，`uploadedBlobBytes=69,011,556`，含源码/清单/元数据/协议头的 `uploadedBytes=98,906,578`。正式结果替代早期计划字节数；不能将 v7 的零缓存 full 样本混作本次 delta。 |
| 传输分段 | client registryResolve 0.301秒、registryDownload 2.086秒、sourceDownload 1.093秒、两次sshUpload累计568.308秒；serverReceive累计582.398秒，sourceAndProofVerify 37.746秒、OCI核验2.070秒、imageLoad 6.590秒。客户端/服务端阶段存在重叠，不能简单相加。 |
| 合回、CLI与业务验收 | [PR50](https://github.com/Gotit-zhx/ai-video/pull/50) 已合回 develop，merge `c0304551dbad584ae468df4f1db7d75fd6507bfa`，checks全部完成且无失败；根会话只读确认 CLI journal `057c96e352e942778475868259fd59cc` 最终为complete且关联上述CD/operation。成本会话回报十月全店真实导出为READY：106,853订单/112,941 SKU，3份XLSX打ZIP，206秒，刷新后可找回同任务并下载；Host.verify通过，8个非app容器ID不变。这些业务结果来自发布owner回执，本轮未重新执行导出。 |

`sshUpload` 计时包住 header/frames 向 SSH stdin 写入直到关闭，client预取下载单独计时；写入可能等待SSH建连、接收端预核验/缓存检查及背压。完整响应等待、接收后OCI重建/导入与业务部署另计，故568.308秒不能全部解释为纯网络带宽。本机raw传输的单次速率不能承诺此生产路径吞吐。

09:58上海时间的只读兼容核验确认：当前版本与源码一致，control/operations未变，schema/transfer均支持 `[1,2]`，无maintenance或未完成主机operation，当前配置hash和source指针符合baseline；v7的v1/full可信manifest仍在保留回滚集合，与当前operations/migrations/unsupported输入一致，实时Flyway指纹兼容。通过Docker绑定的containerd只读content API，在内存核验当前版本、v7、v5的app/frontend manifest/config摘要及linux/amd64平台，未导出/导入镜像、创建主机暂存文件或实际回滚。保留集合为当前版本、v7、v5。

启用 `HEYI_V2_BOOTSTRAPPED=true` 前的只读兼容项包括：业务验收和合回已收尾、五分钟内主机观察、控制面及schema/transfer支持、旧v1可信manifest/镜像、schema兼容、保留回滚集合及无并行维护。上述条件补齐后，根会话刷新主机观察（observedAt=`1794362470`，2026-11-11 10:01:10上海时间，v2026.11.11.1、无maintenance/operations），再设置并读回 `HEYI_V2_BOOTSTRAPPED=true`；runtime bootstrap仍false。本记录不把这些检查视作实际回滚或恢复演练。证据保存在 ignored ledger：`.superpowers/sdd/2026-11-10-cicd-network-acceleration/deployment-v11.11.1/`。

## 开关与 bootstrap 状态

| 仓库变量 | 2026-11-11核对值及边界 |
| --- | --- |
| `HEYI_CD_ENABLED` / `HEYI_OPS_ENABLED` | true，既有正式发布和巡检继续启用。 |
| `HEYI_RUNTIME_CACHE_ENABLED` | true；复用已接受runtime，以下记录实际trusted CI结果。 |
| `HEYI_FAST_CI_ENABLED` / `HEYI_DELTA_TRANSFER_ENABLED` / `HEYI_MANIFEST_V2_ENABLED` | true；首个v2/full与delta生产结果已由上述正式回执核验。 |
| `HEYI_RELEASE_FLOW_ENABLED` | true；真实业务发布已用同一 journal 完成正式接受、回执和必要合回，最终为complete。 |
| `HEYI_V2_BOOTSTRAPPED` / `HEYI_RUNTIME_BOOTSTRAP` | 根会话在上述兼容核验及10:01主机观察后将前者置true并读回确认；后者仍false。首次v2按full验证，后续允许分级候选；不代表module生产样本已完成。 |

## 实际 runtime 复用与耗时

[develop CI 42937845295/1](https://github.com/Gotit-zhx/ai-video/actions/runs/42937845295) 于2026-11-11 09:17:19–09:22:37上海时间成功，full六类套件和三组DB均通过，unexpectedSkips=0。实际下载并核对API摘要的container artifact `13818689678`明确记录 `runtimePreparation=qualified`，媒体字体、容器冒烟和入口门禁通过；full evidence artifact `13819731957`通过run/attempt/树/规则/套件输入核验。

消费的是v7已接受runtime `sha256:19eec2d0511cecb81990d9a8e9c8d7765e2b3fbece138c347caf4486abdf1b42`，recipe `5b06e1c07ec8c4d670dcb71fb8328211925d0dc81f0c8bf13d9960fd07a7a3da`，继承既有321包BOM。BuildKit实际使用导入的本地base，未执行APT冷构建；PR继续保持无私有包凭据的冷路径。

| 实测阶段 | 耗时/字节 |
| --- | --- |
| prepare-runtime整个job / 其中prepare-ci | 39秒 / 23秒 |
| runtime归档上传 / 下载 | 8秒 / **144秒**；artifact `13819441350`为 **835,147,400 bytes** |
| container导入、构建及冒烟 / 整个container job | 59秒 / 227秒 |
| 整次full CI | 318秒；并行阶段不能简单相加。 |
| v7首次full生产传输 | uploadedBytes=489,285,387，cachedBlobBytes=0，SSH上传508.959秒；不能冒充delta样本。 |

23秒仅为准备步骤，**不能声称整个热路径≤30秒或P50/P95达标**。首轮CI `42806039046`曾用307秒；早期本地735测试/33 UI及后续扩展736测试/34 QA均有通过记录。321包本地冷构建55.089秒、立即热构建0.275秒是单次历史样本，不代表持续hosted/生产性能。OCI full/delta隔离导入和image-save回退保持同一manifest/config，未接业务卷。

后续同日trusted CI `42938677039/1`（09:24:02–09:29:06上海时间）也使用上述已接受runtime：prepare-runtime job 30秒、prepare-ci步骤19秒，container下载runtime归档步骤9秒、整个container job88秒，整次CI约304秒。前一轮144秒下载与本轮9秒均为真实暖路径观察；v7首发、runtime复用、下载较快这几种状态不能统称同一个“热样本”，两次CI也不是十次自然同类生产发布样本。

## 网络与耗时定位

11 月 10 日有界只读采样：生产机连接 GitHub API 两次约 289 毫秒，GHCR 两次约 562 毫秒；Docker Hub 两次均在 8 秒连接期限到达时失败。GitHub Release 源码与 JDK 的 35 秒部分下载分别约 21,406 和 25,897 字节/秒，未完成采样；Maven 下载的 8 MiB 范围成功，约 955,550 字节/秒。未修改 DNS 或代理，也未把未完成的采样当作完整文件下载。

GitHub Runner上的CI `42809376810`冷APT阶段344.2秒，其中下载192 MB系统包约5分3秒。现在trusted CI虽省去APT，仍承担上述835 MB归档传输成本；不能把两条路径的等待都归因于大陆服务器。

## 待完成的上线证据

- 成本修复的首个full v2、missing-blob delta、正式附件、必要合回与CLI journal complete已核验；按真实operation保存的唯一样本仍应与后续自然观察使用同一去重身份。
- 六类影子真值表及失败回退已有记录；首次v2/full及旧v1只读兼容资格已补齐。实际module候选、兼容回滚/恢复及运输中断恢复仍须逐项验收，不能把预检或隔离测试写成生产演练。
- 十次自然同类热发布的P50/P95属于后续观察窗口；样本不足写`insufficient_samples`，不制造十次部署，不以代码合入或开关开启代替完整验收。

[小团队快速发布设计](../../design/rapid-delivery-20261110.md)及原生工具链/Boot分层探针属于后续方案。设计和本地探针不是新的生产交付模式，本文件不宣称其已部署。
