#!/usr/bin/env bash
# Manage the AWS Academy Learner Lab credentials for this project.
# They live in ~/.aws/credentials under the [dsp-lab] profile; nothing else in ~/.aws is touched.
# Never prints key values.
#
#   scripts/lab-creds.sh clear   remove the [dsp-lab] keys (e.g. when pausing the course)
#   scripts/lab-creds.sh set     save new keys: copy the block from Learner Lab -> AWS Details ->
#                                AWS CLI "Show" first (it is read from the clipboard), or paste it when asked
#
# Then run things with:  AWS_PROFILE=dsp-lab <command>
set -euo pipefail

PROFILE="dsp-lab"
CREDS_FILE="$HOME/.aws/credentials"
CONFIG_FILE="$HOME/.aws/config"

# Print the file without the [dsp-lab] section (and without trailing blank lines).
without_profile() {
    awk -v header="[$PROFILE]" '
        /^[[:space:]]*\[/ { skip = ($0 == header) }
        skip { next }
        /^[[:space:]]*$/ { blanks++; next }
        { while (blanks > 0) { print ""; blanks-- } print }
    ' "$CREDS_FILE"
}

clear_creds() {
    if [[ ! -f "$CREDS_FILE" ]] || ! grep -qx "\[$PROFILE\]" "$CREDS_FILE"; then
        echo "No [$PROFILE] keys in $CREDS_FILE - nothing to clear."
        return
    fi
    local tmp
    tmp="$(mktemp)"
    without_profile > "$tmp"
    if grep -q '[^[:space:]]' "$tmp"; then
        cat "$tmp" > "$CREDS_FILE"   # other profiles remain: keep the file
    else
        rm -f "$CREDS_FILE"          # it only held the lab keys: delete it
    fi
    rm -f "$tmp"
    echo "Removed [$PROFILE] keys from $CREDS_FILE."
}


# Value of "name=value" (spaces around '=' allowed) from the block.
value_of() {
    printf '%s\n' "$2" | sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" | tr -d '\r[:space:]' | head -n 1
}

set_creds() {
    # Take the block from the clipboard if it looks right, otherwise ask for it in the terminal.
    local block="" from_clipboard=0 key_id secret token
    if command -v pbpaste >/dev/null 2>&1; then
        block="$(pbpaste 2>/dev/null || true)"
    fi
    if [[ "$block" == *aws_session_token* ]]; then
        echo "Using the credentials block from the clipboard."
        from_clipboard=1
    else
        echo "Paste the block from Learner Lab -> AWS Details -> AWS CLI (Show),"
        echo "then press Enter and Ctrl+D:"
        block="$(cat)"
    fi
    key_id="$(value_of aws_access_key_id "$block")"
    secret="$(value_of aws_secret_access_key "$block")"
    token="$(value_of aws_session_token "$block")"
    if [[ -z "$key_id" || -z "$secret" || -z "$token" ]]; then
        echo "ERROR: the block must contain aws_access_key_id, aws_secret_access_key and aws_session_token." >&2
        echo "Nothing was changed." >&2
        exit 1
    fi

    umask 077
    mkdir -p "$HOME/.aws"
    touch "$CREDS_FILE"
    local tmp
    tmp="$(mktemp)"
    without_profile > "$tmp"
    {
        cat "$tmp"
        [[ -s "$tmp" ]] && echo
        echo "[$PROFILE]"
        echo "aws_access_key_id=$key_id"
        echo "aws_secret_access_key=$secret"
        echo "aws_session_token=$token"
    } > "$CREDS_FILE"
    rm -f "$tmp"
    chmod 600 "$CREDS_FILE"
    echo "Saved [$PROFILE] keys to $CREDS_FILE."

    if ! grep -qx "\[profile $PROFILE\]" "$CONFIG_FILE" 2>/dev/null; then
        printf '\n[profile %s]\nregion = us-east-1\n' "$PROFILE" >> "$CONFIG_FILE"
        echo "Added [profile $PROFILE] (region us-east-1) to $CONFIG_FILE."
    fi

    if [[ "$from_clipboard" == 1 ]]; then
        printf '' | pbcopy
        echo "Cleared the clipboard."
    fi

    echo "Checking the keys..."
    if arn="$(AWS_PROFILE="$PROFILE" aws sts get-caller-identity --query Arn --output text 2>&1)"; then
        echo "OK: $arn"
    else
        echo "The keys were saved but AWS rejected them:" >&2
        echo "$arn" >&2
        echo "Is the lab started (green dot)? Copy a fresh block from AWS Details and run 'set' again." >&2
        exit 1
    fi
}

case "${1:-}" in
    clear) clear_creds ;;
    set)   set_creds ;;
    *)
        echo "Usage: $0 clear|set" >&2
        exit 2
        ;;
esac
