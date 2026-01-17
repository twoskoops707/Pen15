#!/usr/bin/env python3
import sys
import os
import time
import struct
import fcntl
import ctypes
import mmap

if len(sys.argv) < 2:
    print("Usage: test_v3.py <fd>")
    sys.exit(1)

fd = int(sys.argv[1])
print(f"FD: {fd}")

# ioctl direction and size encoding
def _IOC(dir, type, nr, size):
    return (dir << 30) | (size << 16) | (ord(type) << 8) | nr

_IOC_WRITE = 1
_IOC_READ = 2

# USBDEVFS ioctls
# These are the raw ioctl numbers for Linux
USBDEVFS_CLAIMINTERFACE = _IOC(_IOC_WRITE, 'U', 15, 4)  # 0x8004550f
USBDEVFS_RELEASEINTERFACE = _IOC(_IOC_WRITE, 'U', 16, 4)  # 0x80045510
USBDEVFS_SETINTERFACE = _IOC(_IOC_WRITE, 'U', 4, 8)
USBDEVFS_RESET = ord('U') << 8 | 20  # 0x5514

# For bulk transfer, the structure is 24 bytes on 64-bit
# struct usbdevfs_bulktransfer {
#     unsigned int ep;      // 4 bytes
#     unsigned int len;     // 4 bytes  
#     unsigned int timeout; // 4 bytes
#     /* 4 bytes padding on 64-bit */
#     void *data;           // 8 bytes on 64-bit
# }
USBDEVFS_BULK = _IOC(_IOC_WRITE | _IOC_READ, 'U', 2, 24)

print(f"BULK ioctl: 0x{USBDEVFS_BULK:08x}")
print(f"CLAIM ioctl: 0x{USBDEVFS_CLAIMINTERFACE:08x}")

# Try reset first
print("\nResetting USB device...")
try:
    fcntl.ioctl(fd, USBDEVFS_RESET, 0)
    print("Reset OK")
    time.sleep(0.5)
except Exception as e:
    print(f"Reset error: {e}")

# Claim interface
print("\nClaiming interface 1...")
try:
    fcntl.ioctl(fd, USBDEVFS_CLAIMINTERFACE, struct.pack('I', 1))
    print("Interface 1 claimed!")
except Exception as e:
    print(f"Claim error: {e}")
    print("Trying interface 0...")
    try:
        fcntl.ioctl(fd, USBDEVFS_CLAIMINTERFACE, struct.pack('I', 0))
        print("Interface 0 claimed!")
    except Exception as e2:
        print(f"Claim 0 error: {e2}")

# Try bulk transfer
print("\nPreparing bulk transfer...")
cmd = b"?\r\n"

# Use array for proper memory handling
import array
out_buf = array.array('b', cmd + b'\x00' * (64 - len(cmd)))
out_addr = out_buf.buffer_info()[0]

# Pack: uint ep, uint len, uint timeout, [4 pad], void* data
# On 64-bit: 4 + 4 + 4 + 4(pad) + 8 = 24 bytes
transfer = struct.pack('IIIIq', 0x01, len(cmd), 2000, 0, out_addr)

print(f"Transfer struct size: {len(transfer)}")
print(f"Sending command: {cmd}")

try:
    result = fcntl.ioctl(fd, USBDEVFS_BULK, transfer)
    print(f"Write result: {result}")
except Exception as e:
    print(f"Write error: {e}")
    import traceback
    traceback.print_exc()

time.sleep(0.3)

# Read
in_buf = array.array('b', [0] * 512)
in_addr = in_buf.buffer_info()[0]

transfer_in = struct.pack('IIIIq', 0x82, 512, 2000, 0, in_addr)

try:
    result = fcntl.ioctl(fd, USBDEVFS_BULK, transfer_in)
    print(f"Read result: {result}")
    if result > 0:
        data = bytes(in_buf[:result])
        print(f"Response: {data}")
except Exception as e:
    print(f"Read error: {e}")
