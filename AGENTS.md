## 1. 技术栈基线

- **JDK 版本**：强制使用 JDK 17+，匹配 Spring Boot 3.x 最低要求
- **核心框架**：Spring Boot 3.4.x、Spring AI 1.0.x 稳定发行版
- **代码风格**：遵循阿里巴巴 Java 开发手册基础规范，统一 4 空格缩进
- **构建工具**：Maven 3.8+，依赖版本统一在父 POM 管理

---

## 2. 包结构与模块边界

### 2.1 分层包结构

```
src/main/java/com/ai/news/
├── task/                      # 定时任务层：仅调度入口，不写业务逻辑
│   └── NewsScheduleTask.java
├── service/                   # 业务服务层：核心流程编排、业务逻辑处理
│   └── NewsDigestService.java # 主业务编排：抓取→过滤→摘要→发送→记录
├── client/                    # 外部调用层：所有第三方交互统一封装
│   ├── rss/
│   │   └── RssFetcherClient.java
│   ├── ai/
│   │   └── AiSummaryClient.java
│   └── mail/
│       └── MailSendClient.java
├── model/                     # 数据模型层
│   ├── dto/                   # 传输对象：层间数据传递
│   │   ├── NewsItem.java      # 单条新闻原始数据
│   │   └── NewsDigest.java    # AI生成的摘要结果
│   └── entity/                # 数据库实体（接入数据库时启用）
│       └── PushRecord.java    # 推送记录：用于去重与幂等校验
├── config/                    # 配置类：按功能拆分
│   ├── AiConfig.java          # 大模型（DashScope）配置
│   ├── MailConfig.java        # 邮件SMTP配置
│   └── ScheduleConfig.java    # 定时任务配置
└── util/                      # 通用工具类：无业务逻辑
    └── NewsDuplicateUtil.java

src/main/resources/
├── application.yml
└── templates/
    └── news-email.html

```

### 2.2 边界约束

- task 层仅调用 service 层方法，禁止直接实现业务逻辑
- 所有第三方外部调用（RSS、AI、SMTP）统一封装在 config 层
- 各层单向依赖，禁止循环依赖，禁止跨层直接调用 SDK
- 代码中的日志打印使用中文日志
---

## 3. AI 交互约束

- Prompt 统一存放于配置文件或常量类，禁止硬编码在业务代码中
- 大模型调用统一通过 Spring AI `ChatClient` 封装，禁止分散调用原生 SDK
- 必须配置调用超时与重试机制，单次失败不得中断整体任务
- AI 输出格式统一约定为 Markdown，便于邮件模板渲染
- 输入新闻内容需做长度截断，控制 Token 消耗

---

## 4. 定时任务约束

- 所有定时任务 Cron 表达式统一配置在 `application.yml` 中，禁止硬编码
- 任务必须保证幂等性：同一日期重复执行，不得重复推送邮件
- 任务异常必须捕获并输出完整日志，禁止静默吞掉异常
- 单任务预计执行时长超过 5 分钟时，需做异步化处理

---

## 5. 邮件与数据约束

- 邮件正文使用 Thymeleaf 模板渲染，禁止手动拼接 HTML 字符串
- SMTP 密码、AI API Key 等敏感配置必须通过环境变量注入，禁止硬编码提交
- 新闻去重逻辑统一由数据层实现，业务层不重复编写去重代码

---

## 6. 安全与合规约束

- `.env`、`application-local.yml` 等本地配置文件必须加入 `.gitignore`
- 日志中禁止打印 API 密钥、邮箱账号密码等敏感信息
- 邮件仅推送新闻摘要与原文链接，禁止全文转载，规避版权风险
- 仅限个人使用，禁止用于批量群发垃圾邮件

## 7. 实体类规范

- `model/dto` 下的实体类统一使用 Lombok 的 `@Data`、`@Builder` 和 `@NoArgsConstructor`，禁止手写 getter 和 setter 方法
- 后续新增实体类沿用上述 Lombok 注解规范；如有必要，可保留构造器中的参数校验逻辑
- `model/dto` 下所有字段必须添加简明准确的中文 JavaDoc 注释，说明字段含义；后续新增或修改字段时同步补充或更新注释

## 8. 依赖注入规范

- Spring Bean 的依赖优先使用 `@Autowired` 或 `@Resource` 等注解注入
- 依赖需要在对象创建阶段参与实例化或初始化时，使用构造方法注入，并明确标注 `@Autowired`
- `@Bean` 方法参数可作为配置类中的依赖注入方式，存在多个同类型 Bean 时必须使用 `@Qualifier` 明确指定
