#!/usr/bin/env python3
"""
Set the admin-panel password (app.admin.password-file).

Generates a strong random password, stores only its PBKDF2-HMAC-SHA256 hash, and prints the
password once. Run it as the user the backend runs as (root), so the file ends up root-only:

    sudo python3 admin_password.py /home/dorothy/CamServer-Backend/data/admin/password

--stdin reads a password of your own choice instead (at least 16 characters). The backend
picks up a new file on the next login attempt; existing sessions stay valid until they expire.
"""

import argparse
import base64
import hashlib
import os
import secrets
import sys
import tempfile

ITERATIONS = 600_000


def encode(password: str, salt: bytes, iterations: int = ITERATIONS) -> str:
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations)
    return "pbkdf2-sha256${}${}${}".format(iterations, base64.b64encode(salt).decode(), base64.b64encode(digest).decode())


def generate() -> str:
    # 20 characters from a 56-symbol alphabet without look-alikes: ~116 bits.
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    return "-".join("".join(secrets.choice(alphabet) for _ in range(5)) for _ in range(4))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("file", help="password hash file, e.g. /home/dorothy/CamServer-Backend/data/admin/password")
    parser.add_argument("--stdin", action="store_true", help="read the password from standard input instead of generating one")
    args = parser.parse_args()

    if args.stdin:
        password = sys.stdin.readline().rstrip("\n")
        if len(password) < 16:
            print("Choose at least 16 characters.", file=sys.stderr)
            return 2
    else:
        password = generate()

    directory = os.path.dirname(os.path.abspath(args.file))
    os.makedirs(directory, mode=0o700, exist_ok=True)
    handle, temporary = tempfile.mkstemp(dir=directory, prefix=".password-")
    try:
        os.fchmod(handle, 0o600)
        with os.fdopen(handle, "w") as out:
            out.write(encode(password, secrets.token_bytes(16)) + "\n")
        os.replace(temporary, args.file)
    except BaseException:
        if os.path.exists(temporary):
            os.unlink(temporary)
        raise

    if not args.stdin:
        print("Admin password (shown once, store it in a password manager):")
        print()
        print("    " + password)
        print()
    print("Hash written to " + os.path.abspath(args.file))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
