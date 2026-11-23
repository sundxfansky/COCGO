#!/bin/bash
set -euo pipefail

# The same Go process serves the API and the built web app.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
checkout="$(printf '%s' "$root" | shasum -a 256 | cut -c 1-12)"
# launchd cannot open files in macOS privacy-protected folders such as Documents.
out="$HOME/Library/Application Support/COCGO/cloud/$checkout"
binary="$out/sun-cloud"
web="$out/web"
log="$out/server.log"
plist="$out/server.plist"
label="com.coc.suncode.cloud.$checkout"
domain="gui/$(id -u)"
job="$domain/$label"

usage() {
    cat <<'EOF'
Usage: sun-server.sh start [--rebuild] | stop | status | logs

Start listens on 0.0.0.0:3280 by default. Open http://127.0.0.1:3280 locally.
Data is stored under
~/Library/Application Support/COCGO/cloud.
Set SUN_CLOUD_LISTEN and SUN_CLOUD_DATA_DIR to override them.
Set PLUGIN_PUBLIC_KEY on start to enable signed plugin uploads.
EOF
}

fail() {
    printf '%s\n' "$*" >&2
    exit 1
}

loaded() {
    launchctl print "$job" >/dev/null 2>&1
}

setting() {
    plutil -extract "EnvironmentVariables.$1" raw "$plist"
}

service_url() {
    local listen="$1"
    if [[ "$listen" == 0.0.0.0:* ]]; then
        listen="127.0.0.1:${listen##*:}"
    fi
    printf 'http://%s' "$listen"
}

healthy() {
    local url="$1" pid
    # A different checkout may already have a healthy server on the same port.
    pid="$(launchctl list | awk -v label="$label" '$3 == label && $1 ~ /^[0-9]+$/ { print $1 }')"
    [[ -n "$pid" ]] &&
        lsof -a -p "$pid" -iTCP:"${url##*:}" -sTCP:LISTEN -t >/dev/null &&
        curl --noproxy '*' --fail --silent --max-time 1 --output /dev/null "$url/healthz"
}

status() {
    local url
    if ! loaded; then
        printf 'Sun Cloud is stopped.\n'
        return 1
    fi
    url="$(service_url "$(setting HTTP_ADDR)")"
    if ! healthy "$url"; then
        printf 'Sun Cloud is unavailable. See %s\n' "$log" >&2
        return 1
    fi
    printf 'Sun Cloud is running at %s\nData: %s\nLog: %s\n' "$url" "$(setting DB_PATH)" "$log"
}

stop_job() {
    if loaded; then
        launchctl bootout "$job"
    fi
}

build_server() {
    local stage="$1"
    command -v go >/dev/null || fail 'Go 1.22+ is required. Install it with brew install go.'
    command -v npm >/dev/null || fail 'Node.js 22.12+ and npm are required. Install them with brew install node.'

    printf 'Building Go backend and React frontend…\n'
    (cd "$root/backend" && CGO_ENABLED=0 go build -mod=readonly -trimpath -o "$stage/sun-cloud" .)
    (cd "$root/web" && npm ci --no-audit --no-fund && npm run build)
    cp -R "$root/web/dist" "$stage/web"
}

write_job() {
    local listen="$1" database="$2"
    touch "$log"
    plutil -create xml1 "$plist"
    plutil -insert Label -string "$label" "$plist"
    plutil -insert ProgramArguments -array "$plist"
    plutil -insert ProgramArguments.0 -string "$binary" "$plist"
    plutil -insert RunAtLoad -bool true "$plist"
    plutil -insert Umask -integer 63 "$plist"
    plutil -insert StandardOutPath -string "$log" "$plist"
    plutil -insert StandardErrorPath -string "$log" "$plist"
    plutil -insert EnvironmentVariables -dictionary "$plist"
    plutil -insert EnvironmentVariables.HTTP_ADDR -string "$listen" "$plist"
    plutil -insert EnvironmentVariables.DB_PATH -string "$database" "$plist"
    plutil -insert EnvironmentVariables.WEB_DIR -string "$web" "$plist"
    if [[ -n "${PLUGIN_PUBLIC_KEY:-}" ]]; then
        plutil -insert EnvironmentVariables.PLUGIN_PUBLIC_KEY -string "$PLUGIN_PUBLIC_KEY" "$plist"
    fi
}

start() {
    # Bind all IPv4 interfaces by default so local Android emulators can connect.
    local rebuild="${1:-}" url listen="${SUN_CLOUD_LISTEN:-0.0.0.0:3280}"
    local data_dir="${SUN_CLOUD_DATA_DIR:-$out/data}"
    local port attempt
    if [[ "$rebuild" != --rebuild ]] && loaded; then
        status
        return
    fi
    [[ "$listen" =~ ^[a-zA-Z0-9.-]+:[0-9]{1,5}$ ]] || fail 'SUN_CLOUD_LISTEN must use host:port.'
    port=$((10#${listen##*:}))
    ((port >= 1 && port <= 65535)) || fail 'SUN_CLOUD_LISTEN port must be between 1 and 65535.'
    listen="${listen%:*}:$port"
    [[ "$data_dir" = /* ]] || data_dir="$PWD/$data_dir"
    url="$(service_url "$listen")"

    umask 077
    mkdir -p "$out" "$data_dir"
    if [[ "$rebuild" == --rebuild || ! -x "$binary" || ! -f "$web/index.html" ]]; then
        stage="$(mktemp -d "$out/.build.XXXXXX")"
        trap 'rm -rf -- "$stage"' EXIT
        # Finish both builds before stopping an existing service.
        build_server "$stage"
        stop_job
        mv -f "$stage/sun-cloud" "$binary"
        rm -rf -- "$web"
        mv "$stage/web" "$web"
    fi

    write_job "$listen" "$data_dir/sun.db"
    launchctl bootstrap "$domain" "$plist"
    for ((attempt = 0; attempt < 40; attempt++)); do
        if healthy "$url"; then
            status
            return
        fi
        sleep 0.25
    done
    stop_job
    fail "Sun Cloud did not become healthy at $url. See $log."
}

case "${1:-}" in
    start)
        [[ $# -eq 1 || ( $# -eq 2 && "$2" == --rebuild ) ]] || { usage >&2; exit 2; }
        start "${2:-}"
        ;;
    stop|status|logs)
        [[ $# -eq 1 ]] || { usage >&2; exit 2; }
        case "$1" in
            stop) stop_job; printf 'Sun Cloud is stopped.\n' ;;
            status) status ;;
            logs) tail -n 40 "$log" ;;
        esac
        ;;
    -h|--help) usage ;;
    *) usage >&2; exit 2 ;;
esac
