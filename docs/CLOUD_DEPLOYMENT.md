# 云端配置服务部署

## 组件

- `backend/`：Go 1.22 API，SQLite 数据库，JWT 登录，bcrypt 密码哈希。配置按用户保存并递增 `version`。
- `web/`：React/Vite 管理网站。登录后编辑 JSON，点击“保存并下发”发布；页面每 30 秒检查一次新版本。
- Android 客户端：`CloudConfigSync` 使用 `If-None-Match` 每 30 秒轮询，只有版本变大才更新本地配置。

## Docker 部署

安装 Docker Compose，在项目根目录创建 `.env`：

```env
JWT_SECRET=replace-with-a-long-random-secret
CORS_ORIGIN=https://config.example.com
```

启动：`docker compose up -d --build`。网站在 `http://localhost:5173`，API 健康检查为 `http://localhost:8080/healthz`。生产环境请在反向代理（Nginx/Cloudflare）上启用 HTTPS，并将 API 域名填入网站构建参数 `VITE_API_URL=https://api.example.com/api/v1`。

## 邮箱验证

通过 `SMTP_HOST`、`SMTP_PORT`、`SMTP_USER`、`SMTP_PASSWORD`、`SMTP_FROM` 配置邮件发送。未配置 SMTP 时验证码只写入后端日志，便于开发环境联调；生产环境必须配置 SMTP，验证码不会返回给浏览器。

## 自动更新

后端和网站镜像使用固定基础镜像版本。更新流程：拉取新代码，执行 `docker compose build --pull`，再执行 `docker compose up -d`。SQLite 数据位于命名卷 `zkq-data`，升级前备份：`docker run --rm -v zkq-data:/data -v "$PWD":/backup alpine tar czf /backup/zkq-data.tgz -C /data .`。

建议 CI 在测试通过后推送带版本号的镜像，并由 Watchtower 或编排平台按版本更新；不要使用 `latest` 自动覆盖生产。客户端逻辑更新（JAR）仍遵循 Android 现有热加载流程，云端配置更新无需重新安装 APK。

## GitHub CI 自动更新

`.github/workflows/cloud-deploy.yml` 会在 `main` 每次推送时编译 Android、构建网站，并将 `zkq-backend` 与 `zkq-web` 推送到 `ghcr.io/<仓库所有者>/`。服务器目录中的 `.env` 建议设置：

```env
BACKEND_IMAGE=ghcr.io/sundxfansky/zkq-backend:latest
WEB_IMAGE=ghcr.io/sundxfansky/zkq-web:latest
```

在 GitHub 仓库 Settings -> Secrets -> Actions 配置 `DEPLOY_HOST`、`DEPLOY_USER`、`DEPLOY_SSH_KEY`、`DEPLOY_PATH`、`DEPLOY_PORT`（可选）和 `GHCR_READ_TOKEN`。配置 `DEPLOY_HOST` 后 workflow 会 SSH 到服务器执行 `docker compose pull` 和 `docker compose up -d`；不配置时仍会构建并发布镜像，不会尝试远程连接。服务器上的 GHCR token 至少需要 `read:packages` 权限。

## API 摘要

`POST /api/v1/auth/register` `{email,password}`；`POST /api/v1/auth/verify` `{email,code}`；`POST /api/v1/auth/login` 返回 Bearer token；`GET /api/v1/config` 获取 `{version,config}`（支持 `If-None-Match`）；`PUT /api/v1/config` `{config:{key:value}}` 发布新版本。
