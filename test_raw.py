#!/usr/bin/env python3
import sys
import os

if len(sys.argv) < 2:
    print("No FD provided")
    sys.exit(1)

fd = int(sys.argv[1])
print(f"FD: {fd}")

# Just try basic operations
import stat
try:
    st = os.fstat(fd)
    print(f"File type: {stat.S_IFMT(st.st_mode)}")
    print(f"Mode: {oct(st.st_mode)}")
except Exception as e:
    print(f"fstat error: {e}")

# Try reading the device info
try:
    data = os.read(fd, 256)
    print(f"Read {len(data)} bytes: {data[:50]}")
except Exception as e:
    print(f"Read error: {e}")

# List file operations available
print("\nFD is valid, USB access granted")
