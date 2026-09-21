# 云端配置服务部署

## 最小化部署

远端平台从本仓库部署根目录的 `docker-compose.yml` 即可。不需要预先创建 `.env`，也不需要设置 JWT、CORS、SMTP 或前端 API 地址。

前提：服务器安装 Docker 和 Docker Compose 插件，已下载本仓库；部署平台需要支持 Compose 从仓库构建镜像。

在项目根目录执行：

```bash
docker compose up -d --build
```

打开 `http://<服务器地址>:3280`。默认只暴露一个 HTTP 端口 `3280`；如果端口已占用，可选设置 `PORT`：

```bash
PORT=8080 docker compose up -d --build
```

后续 Compose 操作保持相同的端口变量，或将 `PORT=8080` 写入可选的 `.env`。反向代理平台将域名指向 `web:80` 或服务器暴露的端口。域名及 HTTPS 证书由部署平台/反向代理管理，不需要重新构建网站。

API 和网站使用同一入口：`/api/v1` 为 API，`/healthz` 为健康检查。后端仅在 Compose 内部网络提供服务，不单独暴露 `8080`。

## 首次网页初始化

1. 打开网站，自动进入初始化页面。
2. 从部署平台的 **backend 容器日志**复制一次性安装码，也可执行 `docker compose logs backend` 查看。
3. 在网页填写安装码、管理员邮箱和密码，完成初始化并自动登录。此步骤不需要 SMTP，也不发送邮箱验证码。
4. 在“配置中心”编辑并发布客户端配置；如果只有管理员使用，到这里即可正常使用，无需继续配置邮件。
5. 需要用户自行注册时，在“部署设置”填写 SMTP 主机、端口、加密方式、用户名、密码/授权码和可选发件人。支持 STARTTLS（通常为 587）和 TLS（通常为 465），要求有效的服务器证书。
6. 保存后点击“使用已保存设置发送测试邮件”，检查管理员邮箱。确认可用后勾选“开放用户注册”并保存。

安装码自动生成，重启前后保持一致，成功初始化后立即失效；不会通过公开 API 返回。不要分享日志中的安装码。管理员权限由后端校验，普通用户只能管理自己的客户端配置。

网页设置保存到 SQLite，立即生效，无需改 `.env`、重建镜像或重启容器。SMTP 密码不会回传给浏览器，编辑时留空表示保持原密码。未配置邮件或未开放注册时，禁止普通用户注册；邮件发送失败会明确报错，验证码不会写入日志或显示在网页上。

JWT 签名密钥第一次启动时自动生成并持久化，容器重启后已有登录凭证继续有效。数据库同时存放账号、客户端配置和平台设置，请保护数据卷与备份文件。

## Android 客户端

客户端云端地址使用网站的同源 API，例如 `https://config.example.com/api/v1`。旧部署若直接使用 `:8080/api/v1`，升级后需要改为网站入口。

`CloudConfigSync` 每 30 秒通过 `If-None-Match` 检查配置版本，更新配置不需要重新安装 APK；客户端 JAR 热加载流程不变。

## 更新与旧部署迁移

```bash
git pull
docker compose up -d --build
```

- 保持原 Compose 项目名、部署目录和 `sun-data` 卷；不要执行 `docker compose down -v`。
- 旧数据库会自动补充管理员字段与设置表，不删除已有账号和客户端配置。
- 首次升级保留原 `.env` 中的 `JWT_SECRET` 和 `SMTP_*` 值，服务会将其导入数据库。之后网页中的持久化设置优先，不再被环境变量覆盖。
- 旧部署进入初始化页面后，可用一次性安装码加原账号邮箱/原密码，将已有账号设为管理员，保留原配置；也可新建管理员账号。
- 默认网站端口从旧版 `5173` 改为 `3280`。如需保持旧网站地址，在 `.env` 设置 `PORT=5173`。

## 数据备份

SQLite 位于 Compose 管理的 `sun-data` 命名卷。实际卷名包含项目名前缀；以下命令复用 Compose 卷定义，避免备份到错误的空卷。建议在维护窗口暂停写入再备份：

```bash
docker compose stop web backend
docker compose run --rm --no-deps -T --entrypoint tar backend -czf - -C /app/data . > sun-data.tgz
docker compose up -d
```

备份包含 SMTP 凭据、签名密钥和用户数据，应限制访问并妥善保管。公网使用前应在部署平台配置 HTTPS，尤其不要通过不可信的明文网络提交管理员密码。

## 可选：CI 镜像部署

本仓库的 `.github/workflows/cloud-deploy.yml` 保留构建和发布 `sun-backend`、`sun-web` 镜像的流程。源码 Compose 部署不依赖 GitHub Secrets 或镜像仓库登录。

已经使用 CI 发布镜像的部署可保留 `.env` 中的 `BACKEND_IMAGE`、`WEB_IMAGE`，并运行：

```bash
docker compose pull backend web
docker compose up -d --no-build
```

配置 GitHub Actions 的 `DEPLOY_HOST`、`DEPLOY_USER`、`DEPLOY_SSH_KEY`、`DEPLOY_PATH`、可选 `DEPLOY_PORT` 和 `GHCR_READ_TOKEN` 后，现有 SSH 自动更新流程仍可使用。首次迁移需先同步新的 Compose 文件到服务器；镜像更新不会替换服务器上的 Compose 文件。

## 本地开发

分别在 `backend/` 运行 `go run .`，在 `web/` 运行 `npm ci && npm run dev`。Vite 将 `/api` 代理到 `127.0.0.1:8080`，无需设置 `VITE_API_URL`。从后端控制台复制安装码完成初始化。

## API 摘要

- `GET /api/v1/setup`：公开的初始化状态与注册开关，不返回敏感设置。
- `POST /api/v1/setup`：`{setupToken,email,password}`，仅首次初始化可用。
- `POST /api/v1/auth/register`：`{email,password}`，要求已开放注册且配置了 SMTP。
- `POST /api/v1/auth/verify`：`{email,code}`。
- `POST /api/v1/auth/login`：返回 Bearer token 和管理员状态。
- `GET /api/v1/auth/me`：检查当前登录者的管理员状态。
- `GET /api/v1/config`：获取 `{version,config}`，支持 `If-None-Match`。
- `PUT /api/v1/config`：`{config:{key:value}}` 发布新版本。
- `GET /api/v1/settings`：仅管理员可读取部署设置，不返回 SMTP 密码。
- `PUT /api/v1/settings`：仅管理员可保存 `{registrationEnabled,smtp:{host,port,security,user,password,from}}`。
- `POST /api/v1/settings/test-email`：仅管理员可用已保存设置发送测试邮件至管理员邮箱。
