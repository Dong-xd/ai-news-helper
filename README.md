# AI News Helper

一个基于 Spring AI 和阿里云百炼通义千问的个人 AI 新闻助手。

## 功能

每天 `08:00`（`Asia/Shanghai`）执行：

```text
定时触发 → 抓取 RSS/Atom → 内存过滤去重 → 通义千问生成结构化 JSON格式 → 程序按引用标记转换为邮件内容 → Thymeleaf 渲染邮件 → SMTP 发送
```

项目不使用数据库，也不持久化新闻、摘要、发送记录或任务状态。所有数据只存在于一次任务执行期间。

## 技术栈

- JDK 17
- Spring Boot 3.4.5
- Spring AI 1.0.0
- Spring AI Alibaba DashScope 1.0.0.2
- 通义千问 `qwen-plus`
- Rome RSS/Atom 解析器
- Thymeleaf HTML 邮件模板
- SMTP 邮件发送

## 配置

复制 `.env.example` 为项目根目录下的 `.env`，设置真实的百炼 API Key 和 SMTP 授权信息。项目已通过 `spring.config.import` 自动加载该文件；不要把真实密钥写入代码或提交到 Git。

主要配置位于 `src/main/resources/application.yml`：

- `news.schedule.cron`：定时表达式，默认每天 08:00
- `news.schedule.zone`：定时任务时区
- `news.rss.sources`：RSS 来源列表及 `enabled` 开关
- `news.rss.max-concurrent-sources`：RSS 来源最大并发数，默认 `4`
- `news.rss.fetch-timeout-seconds`：整批 RSS 抓取超时时间，默认 `60` 秒
- `news.rss.window-hours`：新闻时间窗口，默认 24 小时
- `news.rss.max-items`：每日报告最多 15 条
- `news.ai.system-prompt`：AI 系统提示词
- AI 按配置提示词输出包含四个固定数组的 JSON；程序会严格校验字段、摘要长度、北京时间和原文引用，再直接交给邮件模板渲染
- AI 输出中的 `url` 只接受输入 RSS 原文链接或 `Nxx` 引用标记，程序不会采用模型新生成的链接
- 国外新闻标题和摘要翻译为中文，产品、公司和模型名称保留官方写法
- AI 摘要会按重要性“高 → 中 → 低”排序，同等级按发布时间倒序
- `spring.ai.dashscope.chat.options.model`：通义千问模型
- `spring.ai.dashscope.read-timeout`：DashScope 单次读取超时时间，默认 60 秒，可通过 `AI_DASHSCOPE_READ_TIMEOUT_MS` 调整
- `news.mail.from`、`news.mail.to`：发件人和收件人
- `news.mail.brand-name`：邮件头部和底部显示的品牌名称，默认 `AI速递`
- `news.mail.show-original-links` 与 `news.mail.trusted-link-domains`：原文链接展示开关和可选可信域名白名单；默认展示所有经过 HTTP(S) 校验的新闻标题链接，并清理跟踪参数
- 邮件顶部显示品牌、日期、星期和预计阅读时长；正文按“重磅发布、技术前沿、行业动态、开源工具”分块，邮件中不显示引用编号
- `news.dry-run`：试运行开关，开启时只抓取 RSS 和生成摘要，不发送邮件，默认关闭

## 运行

确保项目根目录存在 `.env`，并配置 JDK 17、Maven 3.8+、DashScope API Key 和 SMTP 参数，然后从项目根目录执行：

```powershell
mvn spring-boot:run
```

应用以定时任务常驻运行，启动后等待定时任务执行。停止进程即可停止任务。

建议调试时先设置 `NEWS_DRY_RUN=true`，避免发送真实邮件。

## 设计限制

- 应用重启后不会记住已发送新闻，跨重启可能出现重复内容。
- 进程内只使用日期标记避免同一天重复发送，多实例部署不提供分布式幂等保证。
- RSS 来源失败会被记录并跳过，不影响其他来源。
- AI 调用失败时会降级为发送 RSS 原始标题和摘要。
- 没有有效新闻时不发送邮件。
- `dry-run` 模式只输出摘要日志，不发送邮件，也不记录当天已发送状态。
