#!/usr/bin/env python3
import sys
import os
import time
import struct
import fcntl

if len(sys.argv) < 2:
    print("Usage: test_flipper.py <fd>")
    sys.exit(1)

fd = int(sys.argv[1])
print(f"File descriptor: {fd}")

# USB CDC endpoints
# Based on device info: EP OUT=0x01, EP IN=0x82

# USBDEVFS ioctl numbers
USBDEVFS_CLAIMINTERFACE = 0x8004550f
USBDEVFS_RELEASEINTERFACE = 0x80045510
USBDEVFS_BULK = 0xc0185502

def usb_bulk_write(fd, endpoint, data, timeout=1000):
    # struct usbdevfs_bulktransfer
    buf = data + b'\x00' * (64 - len(data))  # Pad to 64 bytes
    transfer = struct.pack('IIIq', endpoint, len(data), timeout, id(buf))
    # This won't work directly, need proper ioctl
    return len(data)

def usb_bulk_read(fd, endpoint, size, timeout=1000):
    buf = bytearray(size)
    # Need proper ioctl
    return bytes(buf)

try:
    # Claim interface 1 (CDC Data)
    interface = 1
    try:
        fcntl.ioctl(fd, USBDEVFS_CLAIMINTERFACE, struct.pack('I', interface))
        print(f"Claimed interface {interface}")
    except Exception as e:
        print(f"Claim interface error (may be ok): {e}")

    # Try simple write using os.write
    cmd = b"?\r\n"
    print(f"Sending: {cmd}")

    # The fd from termux-usb is a USB device fd
    # We need to use USBDEVFS_BULK ioctl

    import ctypes

    # Define the bulktransfer structure
    class usbdevfs_bulktransfer(ctypes.Structure):
        _fields_ = [
            ("ep", ctypes.c_uint),
            ("len", ctypes.c_uint),
            ("timeout", ctypes.c_uint),
            ("data", ctypes.c_void_p)
        ]

    # Send data
    data_out = ctypes.create_string_buffer(cmd)
    transfer_out = usbdevfs_bulktransfer()
    transfer_out.ep = 0x01  # Bulk OUT endpoint
    transfer_out.len = len(cmd)
    transfer_out.timeout = 2000
    transfer_out.data = ctypes.addressof(data_out)

    result = fcntl.ioctl(fd, USBDEVFS_BULK, transfer_out)
    print(f"Write result: {result}")

    time.sleep(0.3)

    # Read response
    data_in = ctypes.create_string_buffer(512)
    transfer_in = usbdevfs_bulktransfer()
    transfer_in.ep = 0x82  # Bulk IN endpoint
    transfer_in.len = 512
    transfer_in.timeout = 2000
    transfer_in.data = ctypes.addressof(data_in)

    result = fcntl.ioctl(fd, USBDEVFS_BULK, transfer_in)
    print(f"Read result: {result}")
    print(f"Response: {data_in.raw[:result]}")

except Exception as e:
    import traceback
    print(f"Error: {e}")
    traceback.print_exc()
