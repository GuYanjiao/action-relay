#!/usr/bin/env bash
set -euo pipefail
tag="$1"
export GNUPGHOME
GNUPGHOME="$(mktemp -d "$RUNNER_TEMP/release-gpg.XXXXXX")"
trap 'gpgconf --kill gpg-agent || true; rm -rf -- "$GNUPGHOME"' EXIT
chmod 700 "$GNUPGHOME"
test -n "$ACTION_RELAY_GPG_PRIVATE_KEY"
printf '%s' "$ACTION_RELAY_GPG_PRIVATE_KEY" | gpg --batch --import
expected="$(node -p "JSON.parse(require('fs').readFileSync('release-policy.json')).gitSigningFingerprint")"
gpg --batch --list-secret-keys --with-colons "$expected" | awk -F: '$1=="fpr" {print $10}' | grep -Fxq "$expected"
cat > "$GNUPGHOME/sign-gpg" <<'SH'
#!/usr/bin/env bash
exec gpg --batch --pinentry-mode loopback --passphrase-fd 3 "$@" 3<<<"$ACTION_RELAY_GPG_PASSPHRASE"
SH
chmod 700 "$GNUPGHOME/sign-gpg"
if git show-ref --verify --quiet "refs/tags/$tag"; then
  test "$(git rev-list -n 1 "$tag")" = "$GITHUB_SHA"
else
  git -c user.name=GuYanjiao -c user.email=2980440969@qq.com -c user.signingkey="$expected" \
    -c gpg.program="$GNUPGHOME/sign-gpg" tag -s "$tag" "$GITHUB_SHA" -m "Action Relay $tag"
fi
git verify-tag --raw "$tag" 2> "$GNUPGHOME/verification"
grep '^\[GNUPG:\] VALIDSIG ' "$GNUPGHOME/verification" | grep -Fq "$expected"
git push origin "refs/tags/$tag"
