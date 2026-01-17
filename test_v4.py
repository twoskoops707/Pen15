#!/usr/bin/env python3
import sys
import os
import time
import struct
import fcntl
import array

if len(sys.argv) < 2:
    print("Usage: test_v4.py <fd>")
    sys.exit(1)

fd = int(sys.argv[1])
print(f"FD: {fd}")

# Use exact ioctl numbers from Linux kernel
USBDEVFS_CLAIMINTERFACE = 0x8004550f
USBDEVFS_RELEASEINTERFACE = 0x80045510
USBDEVFS_RESET = 0x5514
USBDEVFS_GETDRIVER = 0x41045508
USBDEVFS_DISCONNECT = 0x80045516
USBDEVFS_CONNECT = 0x80045517

# For 64-bit ARM, bulk struct is 24 bytes
USBDEVFS_BULK = 0xc0185502

print("Trying to reset device first...")
try:
    fcntl.ioctl(fd, USBDEVFS_RESET)
    print("Reset succeeded")
    time.sleep(1)
except Exception as e:
    print(f"Reset: {e}")

# Try to disconnect kernel driver
for iface in [0, 1]:
    print(f"Disconnecting driver from interface {iface}...")
    try:
        fcntl.ioctl(fd, USBDEVFS_DISCONNECT, struct.pack('I', iface))
        print(f"  Disconnected interface {iface}")
    except Exception as e:
        print(f"  {e}")

time.sleep(0.3)

# Claim interfaces
for iface in [1, 0]:
    print(f"Claiming interface {iface}...")
    try:
        fcntl.ioctl(fd, USBDEVFS_CLAIMINTERFACE, struct.pack('I', iface))
        print(f"  Claimed interface {iface}!")
        break
    except Exception as e:
        print(f"  {e}")

# Now try bulk
print("\nSending test command...")
cmd = b"?\r\n"

# Create aligned buffer
out_buf = array.array('b', cmd + b'\x00' * (64 - len(cmd)))
out_addr = out_buf.buffer_info()[0]

# struct usbdevfs_bulktransfer for 64-bit:
# unsigned int ep (4) + unsigned int len (4) + unsigned int timeout (4) + padding (4) + void *data (8)
bulk_out = struct.pack('=IIIIq', 0x01, len(cmd), 2000, 0, out_addr)

print(f"Bulk struct: {len(bulk_out)} bytes")
print(f"Data ptr: 0x{out_addr:x}")

try:
    # Use ioctl with mutable buffer
    result = fcntl.ioctl(fd, USBDEVFS_BULK, bulk_out)
    print(f"Write OK: {result} bytes")
except OSError as e:
    print(f"Write error: {e}")

time.sleep(0.3)

# Read response
in_buf = array.array('b', [0] * 512)
in_addr = in_buf.buffer_info()[0]

bulk_in = struct.pack('=IIIIq', 0x82, 512, 2000, 0, in_addr)

try:
    result = fcntl.ioctl(fd, USBDEVFS_BULK, bulk_in)
    print(f"Read OK: {result} bytes")
    if result > 0:
        data = bytes(in_buf[:result])
        print(f"Response: {data.decode('utf-8', errors='replace')}")
except OSError as e:
    print(f"Read error: {e}")

print("\nDone.")
