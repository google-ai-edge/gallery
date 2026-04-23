# essay-polisher — 使用说明

## 这是什么

一个 AI Edge Gallery **JS Skill**，将 Prompt E（去AI味润色指令）封装成手机端随时可用的技能。
该 skill 包含 `SKILL.md + scripts/index.html`，可通过 `run_js` 执行预处理并返回结果。

## 怎么用（手机 App 内）

### 方法一：从 URL 加载（推荐）

1. 在 AI Edge Gallery app 进入 **Agent Skills** 模式
2. 点击 **Skills chip** 打开技能管理器
3. 点击 **(+)** → 选择 **Load skill from URL**
4. 输入 URL（需要先把 SKILL.md 托管到 GitHub Pages，见下方）

### 方法二：Android 本地文件（adb）

```bash
adb push essay-polisher/ /sdcard/Download/essay-polisher/
```

然后在 app 里选 **Import local skill** → 选择该文件夹。

---

## 托管到 GitHub Pages（一次性设置）

1. 把 `essay-polisher/` 文件夹（含 `scripts/index.html`）推送到你的 GitHub repo
2. 在 repo 根目录创建空文件 `.nojekyll`（防止 Jekyll 处理 .md 文件）
3. 开启 GitHub Pages（Settings → Pages → Deploy from branch → main）
4. 等待部署完成后，URL 格式为：

```
https://your-username.github.io/your-repo/essay-polisher
```

在 app 里填入这个 URL 即可加载。

> 注意：不能用 `raw.githubusercontent.com`，MIME type 不对会加载失败。
> 必须用 GitHub Pages 的 `*.github.io` 域名。

---

## 触发方式（说什么话会激活这个 skill）

| 中文 | 英文 |
|------|------|
| 帮我润色这篇PS | Polish my personal statement |
| 去AI味 | Remove AI tone |
| 这篇SOP太机械了 | This SOP sounds robotic |
| 帮我改改这篇文书 | Edit my essay draft |

直接粘贴文书内容，LLM 会自动识别并调用此 skill。

---

## 这个 skill 做了什么（7步润色流程）

```
Step 1: 开头钩子检测 → 如果开头不抓人，重写
Step 2: AI词汇清除 → 删掉 furthermore/tapestry/delve into 等
Step 3: 死句删除   → 删掉不含具体信息的废话句子
Step 4: 感官细节   → 在核心经历处加 1-2 个具体感官描写
Step 5: 语气一致性 → 统一正式/口语风格
Step 6: 结尾改写   → 呼应开头，有力收尾
Step 7: 字数控制   → 超长时从中间段落裁剪
```

---

## 和 writing-prompts.md 的关系

| 工具 | 用途 | 使用时机 |
|------|------|---------|
| Prompt A-D | 从零生成初稿 | 客户填完问卷后，在电脑上生成 |
| **essay-polisher skill** | 润色已有草稿 | 手机上随时用，或给客户用 |

两者配合：Prompt A 生成初稿 → essay-polisher 润色 → 交付给客户
