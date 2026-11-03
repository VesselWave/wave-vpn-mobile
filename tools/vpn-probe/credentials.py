#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Collect QA account credentials from hidden terminal prompts into a private file."""
import argparse
import getpass
import json
import os
from pathlib import Path
import stat
import sys


def write_credentials(path, username, password):
    raw = Path(path).expanduser()
    if raw.is_symlink():
        raise ValueError('Refusing a symbolic-link destination')
    destination = raw.resolve()
    if any((parent / '.git').exists() for parent in destination.parents):
        raise ValueError('Credentials must be outside Git checkouts')
    destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = destination.parent.stat()
    if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) & 0o077:
        raise ValueError('Use a private directory owned by your user (mode 0700)')
    if not username or not password:
        raise ValueError('Both fields must be nonempty')
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0)
    descriptor = os.open(destination, flags, 0o600)
    try:
        with os.fdopen(descriptor, 'w') as stream:
            json.dump({'username': username, 'password': password}, stream)
            stream.write('\n')
    except BaseException:
        destination.unlink(missing_ok=True)
        raise
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path('/tmp/wave-proton-login/account.json'))
    args = parser.parse_args()
    if not sys.stdin.isatty():
        raise ValueError('Run this command in your own interactive terminal')
    if args.output.expanduser().exists():
        raise ValueError('Destination already exists; use a new --output path')
    username = getpass.getpass('Proton username/email (hidden): ')
    password = getpass.getpass('Proton password (hidden): ')
    if password != getpass.getpass('Confirm password (hidden): '):
        raise ValueError('Passwords did not match; nothing saved')
    destination = write_credentials(args.output, username, password)
    print('Saved privately: ' + str(destination))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError) as error:
        print(str(error) if isinstance(error, ValueError) else 'Could not save credentials; no values logged.', file=sys.stderr)
        sys.exit(2)
    except (KeyboardInterrupt, EOFError):
        print('\nCancelled; no credentials saved.', file=sys.stderr)
        sys.exit(130)
