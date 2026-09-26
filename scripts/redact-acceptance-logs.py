#!/usr/bin/env python3
"""Remove development OTPs from disposable-stack CI artifacts, retaining failure context."""
import re
import sys
for line in sys.stdin:
    line = re.sub(r'(\[DEV ONLY\] verification code for .+? is )\S+', r'\1[redacted]', line)
    print(line, end='')
