#!/usr/bin/env python3
"""Export an already-authenticated AWS CLI *temporary* session to paste into Android.

Usage: python3 tools/export_session.py [aws-profile]
Requires AWS CLI v2; e.g. aws sso login --profile korvai-readonly.
Do not put the output in git, email, or public chat.
"""
import json, subprocess, sys
profile = sys.argv[1] if len(sys.argv) > 1 else 'default'
cmd = ['aws', 'configure', 'export-credentials', '--profile', profile, '--format', 'process']
raw = json.loads(subprocess.check_output(cmd, text=True))
if not all(raw.get(k) for k in ('AccessKeyId', 'SecretAccessKey', 'SessionToken', 'Expiration')):
    raise SystemExit('This profile does not provide expiring session credentials. Use IAM Identity Center (SSO) or another temporary-credential profile.')
print(json.dumps({k: raw[k] for k in ('AccessKeyId', 'SecretAccessKey', 'SessionToken', 'Expiration')}, indent=2))
