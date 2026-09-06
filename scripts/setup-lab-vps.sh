#!/usr/bin/env bash
# Run on the jump VPS as root. Does not store or print credentials.
set -euo pipefail

REPO_BARE="${LAB_REPO_BARE:-/opt/repos/ARDTT.git}"
WORK_TREE="${LAB_WORK_TREE:-/opt/ardtt-lab}"
DIST_LAB="${LAB_DIST_DIR:-/opt/ardtt-distribution/dist/lab}"
REMOTE_PORT="${LAB_REMOTE_PORT:-7422}"
HOST_IP="$(hostname -I | awk '{print $1}')"

mkdir -p /opt/repos "$DIST_LAB"

if [[ ! -d "$REPO_BARE" ]]; then
  git init --bare "$REPO_BARE"
  git --git-dir="$REPO_BARE" symbolic-ref HEAD refs/heads/lab
fi
git --git-dir="$REPO_BARE" config --local receive.denyCurrentBranch ignore
git --git-dir="$REPO_BARE" update-server-info

install -m 0755 /dev/stdin /usr/local/bin/ardtt-lab-post-receive <<'HOOK'
#!/usr/bin/env bash
set -euo pipefail
REPO_BARE=/opt/repos/ARDTT.git
WORK_TREE=/opt/ardtt-lab
if [[ ! -d "$WORK_TREE/.git" ]]; then
  rm -rf "$WORK_TREE"
  git clone --branch lab "$REPO_BARE" "$WORK_TREE"
else
  git -C "$WORK_TREE" fetch origin lab
  git -C "$WORK_TREE" reset --hard origin/lab
fi
if [[ -x "$WORK_TREE/scripts/ardtt-lab" ]]; then
  install -m 0755 "$WORK_TREE/scripts/ardtt-lab" /usr/local/bin/ardtt-lab
fi
git --git-dir="$REPO_BARE" update-server-info
HOOK
ln -sfn /usr/local/bin/ardtt-lab-post-receive "$REPO_BARE/hooks/post-receive"

cat >"$DIST_LAB/index.html" <<EOF
<!doctype html>
<meta charset="utf-8">
<title>ARDTT Lab</title>
<body style="font-family:sans-serif;max-width:40rem;margin:2rem auto;line-height:1.45">
<h1>ARDTT Lab</h1>
<p>Промежуточный сервер для живой проверки. Канал Lab слушает только localhost.</p>
<ul>
<li>SSH: <code>root@$(hostname -I | awk '{print $1}')</code> порт 22</li>
<li>Реверс телефона: <code>127.0.0.1:$REMOTE_PORT</code></li>
<li>Репозиторий: <code>ssh://root@${HOST_IP}${REPO_BARE}</code></li>
<li>Рабочая копия: <code>$WORK_TREE</code></li>
<li>Агент: <code>ardtt-lab status</code> (уже на сервере) или <code>./scripts/ardtt-lab --ssh root@${HOST_IP} status</code></li>
</ul>
<p>В приложении Lab на телефоне: тот же хост и пользователь, пароль вводится на устройстве, Wi‑Fi выкл.</p>
</body>
EOF

echo "lab repo $REPO_BARE"
echo "work tree $WORK_TREE (filled after first push)"
echo "page $DIST_LAB/index.html"
sshd -T | grep -E 'allowtcpforwarding|gatewayports' || true
