# DevVault 服务端

DevVault 客户端的登录与云同步服务。Kotlin + Ktor + MySQL，Gradle 多模块管理。

**端到端加密**：账号密码和待办在客户端加密后才上传，服务端只保存密文，不接触主密码，也无法解密任何数据。即使服务器或数据库泄露，没有用户的主密码也看不到内容。协议细节见客户端仓库中的 [docs/server-api.md](https://github.com/Chihiro-bit/devvault/blob/main/docs/server-api.md)。

客户端在「设置 → 账号与云同步」中填写服务器地址即可连接：可以使用统一提供的服务器，也可以按本文自行部署。

## 模块

```
dev-vault-server/
├── core/          接口模型、错误类型、校验规则、哈希等加密工具（纯 Kotlin，无框架依赖）
├── persistence/   MySQL 连接池（HikariCP）、Flyway 迁移、表映射（Exposed）与仓储
├── service/       业务逻辑：注册 / 登录 / 会话 / 修改密码 / 登录限流、同步（拉取与推送）
└── app/           Ktor HTTP 服务：路由、认证、错误处理、配置，可执行入口
```

依赖方向：`app → service → persistence → core`。依赖版本集中在 `gradle/libs.versions.toml`。

## 快速部署（Docker Compose）

需要 Docker 与 Docker Compose。

```bash
cd dev-vault-server
cp .env.example .env      # 修改其中的数据库密码
docker compose up -d
curl http://127.0.0.1:8080/api/server/info
```

服务默认只监听 `127.0.0.1:8080`。对外提供服务前请在前面加一层 HTTPS 反向代理，例如 Caddy：

```
devvault.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

或 Nginx：

```nginx
server {
    listen 443 ssl;
    server_name devvault.example.com;
    # ssl_certificate / ssl_certificate_key ...
    client_max_body_size 50m;
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

客户端中填写 `https://devvault.example.com` 即可。

## 手动部署

需要 JDK 17+ 和 MySQL 8.0+。

```sql
CREATE DATABASE devvault CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'devvault'@'%' IDENTIFIED BY '强密码';
GRANT ALL ON devvault.* TO 'devvault'@'%';
```

```bash
cd dev-vault-server
./gradlew :app:installDist          # Windows: gradlew.bat :app:installDist
export DEVVAULT_DB_URL='jdbc:mysql://127.0.0.1:3306/devvault?serverTimezone=UTC'
export DEVVAULT_DB_PASSWORD='强密码'
app/build/install/devvault-server/bin/devvault-server
```

首次启动会自动建表（Flyway），后续升级同样自动迁移。

## 配置

所有配置都在 `app/src/main/resources/application.conf`，可用环境变量覆盖：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DEVVAULT_HOST` / `DEVVAULT_PORT` | `0.0.0.0` / `8080` | 监听地址 |
| `DEVVAULT_DB_URL` | `jdbc:mysql://127.0.0.1:3306/devvault?...` | JDBC 地址 |
| `DEVVAULT_DB_USER` / `DEVVAULT_DB_PASSWORD` | `devvault` / 空 | 数据库账号 |
| `DEVVAULT_DB_POOL_SIZE` | `10` | 连接池大小 |
| `DEVVAULT_NAME` | `DevVault` | 显示在客户端的服务器名称 |
| `DEVVAULT_ALLOW_REGISTRATION` | `true` | 是否开放注册。私有服务器创建完账号后可关闭 |
| `DEVVAULT_SESSION_DAYS` | `30` | 登录有效期（天），有访问时自动续期 |
| `DEVVAULT_TRUST_PROXY` | `false` | 部署在反向代理之后时设为 `true`，从 `X-Forwarded-For` 获取真实 IP（用于登录限流） |

## 数据表

| 表 | 内容 |
| --- | --- |
| `users` | 登录名、显示名称、authKey 的 PBKDF2 哈希、scrypt 参数、被包裹的数据密钥（密文）、同步版本号 |
| `sessions` | 登录令牌的 SHA-256、设备名、有效期 |
| `sync_items` | 每条同步记录的类型、随机 id、版本号、是否删除、修改时间、**密文** |
| `server_meta` | 服务端自动生成的内部密钥（用于给不存在的账号生成假 salt） |

服务端能看到的只有：登录名、显示名称、邮箱（选填）、记录数量与类型、修改时间。账号、密码、待办标题等内容全部是密文。

## 安全说明

- 主密码在客户端经 scrypt（N=2^17）派生，服务端收到的 `authKey` 再经 PBKDF2-SHA256 哈希保存
- 登录令牌为 256 位随机数，数据库只保存其 SHA-256；注销立即失效；修改主密码后其他设备的登录全部失效
- 同一「账号 + IP」15 分钟内连续失败 5 次后暂时禁止登录（单实例内存实现，多实例请在网关层限流）
- 账号不存在时预登录接口返回稳定的假参数，登录比对耗时一致，不泄露账号是否存在
- 用户忘记主密码时，服务端**无法**重置或找回数据，只能重新注册；本机数据不受影响，登录新账号后会重新上传

## 开发

```bash
./gradlew build                     # 编译 + 单元测试
./gradlew :app:run                  # 本地运行（读取 application.conf 与环境变量）
```

集成测试需要一个 MySQL，设置环境变量后运行（未设置时自动跳过）：

```bash
docker run -d --name devvault-mysql -p 127.0.0.1:3307:3306 \
  -e MYSQL_DATABASE=devvault_test -e MYSQL_USER=devvault -e MYSQL_PASSWORD=devvault -e MYSQL_ROOT_PASSWORD=root mysql:8.4

DEVVAULT_TEST_DB_URL='jdbc:mysql://127.0.0.1:3307/devvault_test' \
DEVVAULT_TEST_DB_USER=devvault DEVVAULT_TEST_DB_PASSWORD=devvault ./gradlew test
```

数据库结构变更时在 `persistence/src/main/resources/db/migration` 下新增 `V2__xxx.sql`，不要修改已发布的迁移文件。接口有不兼容变更时递增 `app` 中的 `API_VERSION`，客户端会据此提示升级。
