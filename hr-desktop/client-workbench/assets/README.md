# Ai boss

两端显示名称统一为 `Ai boss`。黑底、白色 Ai 字形，轮廓在小尺寸保留清晰对比。

- `ai-boss.svg`：可编辑矢量母版。
- `ai-boss.png`：应用顶栏使用的 1024px 方形图标。
- `ai-boss-app.png`：带透明外边距与圆角的桌面图标。
- `ai-boss.icns`：Mac Finder 与 Dock 图标。
- `ai-boss.ico`：Windows 启动 EXE 图标，包含 16–256px 共九个尺寸。
- `brand.json`：构建器与应用读取的显示名称、启动文件名。

安装包直接使用已提交的图片，不需要用户下载图片或安装图片处理依赖。
设计工具为内置 `image_gen`，没有使用 API/CLI fallback。两次生成没有返回可读取的
本地文件，因此打包资产采用本目录可重建的 SVG 设计，不引用临时生成结果。
设计提示：为 Ai boss 设计黑底白色、粗线条几何字形的桌面图标；保持小尺寸可辨识，
无阴影、无渐变、无装饰、无完整名称文字。最终字形采用 Ai。

需要修改时编辑 SVG，在安装了 `sharp` 的 macOS Node 环境运行：

```sh
node build-icons.cjs
```

改名只影响展示和自有入口。保留 Mac 内部 `CFBundleName`、`HeyiWorkbench`、
Bundle ID、用户资料目录、Cookie 分区与 Keychain 标识；Windows 保留原 EXE、
ASAR 及 `HeyiBossWorkbench` 资料目录。不要为了品牌改名迁移或清理登录资料。
