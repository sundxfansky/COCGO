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

## macOS 本地部署（无需 Docker）

首次构建需要 Go 1.22+、Node.js 22.12+ 和 npm，可通过 `brew install go node` 安装。脚本使用当前 macOS 登录用户的 `launchd` 会话在后台运行一个 Go 进程，同时提供网站和 API。

在项目根目录运行：

```bash
./scripts/macos/sun-server.sh start
./scripts/macos/sun-server.sh status
./scripts/macos/sun-server.sh logs
./scripts/macos/sun-server.sh stop
```

- 打开 `http://127.0.0.1:3280`，首次初始化时使用 `logs` 输出中最新的一次性安装码。首次启动会构建前后端，之后复用产物。更新代码后执行 `./scripts/macos/sun-server.sh start --rebuild` 重新构建并启动。
- SQLite 和日志默认保存在 `~/Library/Application Support/COCGO/cloud/` 下当前检出目录对应的子目录中，完整路径可用 `status` 查看。每次启动时可用 `SUN_CLOUD_DATA_DIR` 指定当前用户的后台进程可访问的数据目录。
- 默认监听 `0.0.0.0:3280`，同时支持本机通过 `127.0.0.1` 访问和局域网设备接入。Android 客户端填写 `http://<Mac 的局域网 IP>:3280/api/v1`。只在 Mac 本机使用时，可设置 `SUN_CLOUD_LISTEN=127.0.0.1:3280`。
- 若需要发布签名插件，在启动时传入 `PLUGIN_PUBLIC_KEY`，见[插件发布指南](PLUGIN_RELEASE.md#一次性配置)。

## Windows 本地部署（无需 Docker）

后端设置 `WEB_DIR` 后会直接托管前端静态文件，一个 `sun-cloud.exe` 进程即可替代 Compose 中的 backend + nginx，API、网站和 `/healthz` 仍是同一入口。

前提：安装 Go 1.22+ 和 Node.js 22（仅构建时需要）：

```bat
winget install -e --id GoLang.Go
winget install -e --id OpenJS.NodeJS.LTS
```

在项目根目录运行（首次自动构建，之后直接启动）：

```bat
scripts\windows\sun-server.bat
```

- 打开 `http://127.0.0.1:3280`，安装码打印在该控制台窗口中，后续初始化步骤与下文相同。
- 数据默认保存在 `backend\data\sun.db`（已 gitignore）；可用 `-DataDir D:\sun-data` 指定其他目录，`-Port 8080` 换端口。
- `git pull` 后加 `-Rebuild` 重新构建；`-BuildOnly` 只生成 `build\sun-server\`。该目录可整体复制到没有 Go/Node 的 Windows 电脑，双击其中的 `start.bat` 运行，数据保存在旁边的 `data\`。
- 手机/模拟器访问时，客户端云端地址填 `http://<电脑局域网 IP>:3280/api/v1`。首次启动 Windows 防火墙会弹窗询问，需允许专用网络；只在本机使用可传 `-Listen 127.0.0.1:3280` 不对外监听。
- 本地部署为明文 HTTP，只建议在可信局域网使用；需要公网访问时请在前面加 HTTPS 反向代理。
- 备份：停止服务后复制数据目录下的 `sun.db*` 文件即可。

## 首次网页初始化

1. 打开网站，自动进入初始化页面。
2. 从服务日志复制一次性安装码。Compose 部署可执行 `docker compose logs backend`，macOS 可执行 `./scripts/macos/sun-server.sh logs`，Windows 可查看启动窗口。
3. 在网页填写安装码、管理账号邮箱和密码，完成初始化并自动登录。邮箱用于登录标识，不需要配置 SMTP，也不发送验证码。页面使用一个管理账号统一管理设备，不提供用户注册流程。
4. 在网页复制连接 Token，在 Android 内置 Web 页的远端同步设置中填写服务端地址、Token 和设备名称，然后启用同步；设备会自动注册到当前管理账号下。
5. 在服务端页面顶部切换设备标签，再在“主页设置 / 账号设置”中编辑并发布该设备的配置。每台设备的内容和版本互相独立，客户端按版本自动接收更新。

安装码自动生成，重启前后保持一致，成功初始化后立即失效；不会通过公开 API 返回。不要分享日志中的安装码。管理员权限由后端校验，普通用户只能管理自己的客户端配置。

配置和设备信息保存到 SQLite，发布后按同步周期生效，无需改 `.env`、重建镜像或重启容器。客户端使用的 Token 属于当前管理会话；该会话退出或到期后，需要重新登录并更新客户端 Token。Token 只应填入自己的客户端，不要分享给其他人。

历史 SMTP、注册及邮箱验证 API 保留用于兼容旧部署，不属于当前页面的使用流程。旧部署已保存的 SMTP 密码不会回传给浏览器；新部署无需邮件设置。

JWT 签名密钥第一次启动时自动生成并持久化，容器重启后已有登录凭证继续有效。数据库同时存放账号、客户端配置和平台设置，请保护数据卷与备份文件。

## Android 客户端

网页的“接入设备”弹窗可生成独立的接入 Token（有效期 30 天）。退出浏览器管理会话不会注销这些设备连接；到期后重新生成并在客户端更新。每个 Token 代表同一管理账号的一次独立会话，请仅提供给自己的设备。

客户端云端地址使用网站的同源 API，例如 `https://config.example.com/api/v1`。旧部署若直接使用 `:8080/api/v1`，升级后需要改为网站入口。

客户端始终使用 APK 内置的 Web 设置页面，保留“主页设置 / 账号设置”两个 Tab。启用远端同步后，本地设置只读，配置在服务端 Web 页面修改并发布；关闭远端同步后恢复本地编辑。

在服务端选中设备并打开“设备工具”，可以设置功能插件的启停、提取存档的版本和序号，以及 QQ 授权库序号。这些设置随设备配置发布。选择“获取 QQ 授权”时，在线设备会打开授权工具，页面下方显示当前扫码二维码。扫码成功后授权保存在该设备上，二维码会从网页移除。存档提取、删除、插件安装和截屏反馈在设备端执行。

客户端以安装时生成并保存在私有存储的稳定设备标识注册当前设备。服务端顶部的设备标签用于选择目标设备，设备名称可修改，配置不会因为重名而合并。新设备从空配置、版本 `0` 开始，不复制其他设备的设置；同一管理账号下的各设备分别维护版本。其他账号不能读取、修改或列出这些设备。

`CloudConfigSync` 每 30 秒请求 `/api/v1/devices/{设备标识}/config/version`，该接口只查询并返回当前设备的版本号，不读取配置正文。首次连接或版本变化时，才通过 `/api/v1/devices/{设备标识}/config` 下载完整设置。本地持久化成功后才确认版本，失败后会重试，客户端 JAR 热加载流程不变。网页保存需要携带读取时的 `If-Match` 版本；其他页面已发布该设备的新版本时返回 `409`，保留草稿并提示合并，不覆盖较新的配置。

升级已有服务时，仅有已保存配置的账号会自动出现“默认设备”（标识 `default`）。它直接使用原有配置及版本；旧版 `/api/v1/config` 接口与该设备共享同一份数据，避免新旧客户端产生两条版本历史。新注册设备不会自动继承默认设备的数据。

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
- `POST /api/v1/auth/device-token`：使用已登录管理会话创建独立设备接入会话，返回 `{token,expiresAt}`，有效期 30 天；`expiresAt` 为 Unix 秒。
- `GET /api/v1/devices`：列出当前管理账号的设备，返回 `{devices:[{id,name,version,updatedAt}]}`；时间为 Unix 秒。
- `POST /api/v1/devices`：以 `{id?,name}` 添加设备；省略 `id` 时服务端生成。相同账号和标识重复注册不改名，不重置版本或配置。设备标识允许 1–64 个英文字母、数字、下划线或连字符，名称为 1–80 个字符。
- `PATCH /api/v1/devices/{id}`：以 `{name}` 重命名当前账号的设备，不改变其配置版本。
- `GET /api/v1/devices/{id}/config/version`：当前设备的轻量版本检查，返回 `{version}`，禁止缓存；未知设备返回 `404`。
- `GET /api/v1/devices/{id}/config`：获取当前设备 `{version,config}`，支持 `If-None-Match`。
- `PUT /api/v1/devices/{id}/config`：携带 `If-Match`，以 `{config:{key:value}}` 发布该设备的新版本；版本冲突返回 `409`。
- `GET /api/v1/config/version`：经过身份验证的轻量版本检查，返回 `{version}`；尚未发布配置时为 `0`，响应禁止缓存。
- `GET /api/v1/config`：获取 `{version,config}`，支持 `If-None-Match`。
- `PUT /api/v1/config`：携带 `If-Match: <读取的版本号>`，以 `{config:{key:value}}` 发布新版本；版本冲突返回 `409`。
- `GET /api/v1/settings`：仅管理员可读取部署设置，不返回 SMTP 密码。
- `PUT /api/v1/settings`：仅管理员可保存 `{registrationEnabled,smtp:{host,port,security,user,password,from}}`。
- `POST /api/v1/settings/test-email`：仅管理员可用已保存设置发送测试邮件至管理员邮箱。
