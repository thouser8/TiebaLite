# TiebaLite

百度贴吧第三方客户端（非官方），个人自用修改版。

## 开源说明

- **许可证**：[GPL-3.0](LICENSE)。本仓库是修改后的衍生作品，同样以 GPL-3.0 发布，完整许可证文本见 `LICENSE`。
- **来源与署名**：fork 自 [makise2060/FluxTie](https://github.com/makise2060/FluxTie)，其 fork 自 [HuanCheng65/TiebaLite](https://github.com/HuanCheng65/TiebaLite)。原始著作权归各上游作者所有，源码中的版权声明与署名一律保留。
- **修改说明**：相对上游的改动——更换应用名与包名（`com.thouser8.tiebalite`，可与其他版本共存安装）、修复吧内置顶与吧规折叠状态不记忆、修复首次进吧落在热门页、构建流程改为固定签名并发布 APK。逐条记录见本仓库 git 提交历史。
- **免责**：本项目仅供学习交流，不用于商业用途；非百度或贴吧官方客户端，相关商标与内容权利归其权利人所有。

## 构建

```bash
./gradlew assembleRelease
```
