#!/usr/bin/env python3
import sys
import os
import time
import struct
import fcntl
import ctypes
import array

if len(sys.argv) < 2:
    print("Usage: test_flipper2.py <fd>")
    sys.exit(1)

fd = int(sys.argv[1])
print(f"File descriptor: {fd}")

# USBDEVFS ioctl numbers (ARM/Android)
USBDEVFS_CLAIMINTERFACE = 0x8004550f
USBDEVFS_RELEASEINTERFACE = 0x80045510
USBDEVFS_BULK = 0xc0185502
USBDEVFS_DISCONNECT = 0x5516  # Disconnect kernel driver
USBDEVFS_CONNECT = 0x5517

# Define the bulktransfer structure
class usbdevfs_bulktransfer(ctypes.Structure):
    _fields_ = [
        ("ep", ctypes.c_uint),
        ("len", ctypes.c_uint),
        ("timeout", ctypes.c_uint),
        ("data", ctypes.c_void_p)
    ]

try:
    # Try to disconnect kernel driver from interface 1 (CDC Data)
    for iface in [0, 1]:
        try:
            print(f"Disconnecting kernel driver from interface {iface}...")
            fcntl.ioctl(fd, USBDEVFS_DISCONNECT, struct.pack('I', iface))
        except Exception as e:
            print(f"  Disconnect {iface}: {e}")

    time.sleep(0.2)

    # Claim interface 1 (CDC Data)
    interface = 1
    try:
        fcntl.ioctl(fd, USBDEVFS_CLAIMINTERFACE, struct.pack('I', interface))
        print(f"Claimed interface {interface}")
    except Exception as e:
        print(f"Claim interface error: {e}")
        # Try interface 0
        try:
            fcntl.ioctl(fd, USBDEVFS_CLAIMINTERFACE, struct.pack('I', 0))
            print("Claimed interface 0 instead")
        except Exception as e2:
            print(f"Claim interface 0 error: {e2}")

    # Send ? command
    cmd = b"?\r\n"
    print(f"\nSending: {cmd}")

    # Create buffer for write
    data_out = ctypes.create_string_buffer(cmd)
    transfer_out = usbdevfs_bulktransfer()
    transfer_out.ep = 0x01  # Bulk OUT endpoint
    transfer_out.len = len(cmd)
    transfer_out.timeout = 2000
    transfer_out.data = ctypes.addressof(data_out)

    result = fcntl.ioctl(fd, USBDEVFS_BULK, transfer_out)
    print(f"Write result: {result} bytes")

    time.sleep(0.3)

    # Read response
    data_in = ctypes.create_string_buffer(512)
    transfer_in = usbdevfs_bulktransfer()
    transfer_in.ep = 0x82  # Bulk IN endpoint
    transfer_in.len = 512
    transfer_in.timeout = 2000
    transfer_in.data = ctypes.addressof(data_in)

    result = fcntl.ioctl(fd, USBDEVFS_BULK, transfer_in)
    print(f"Read result: {result} bytes")
    
    if result > 0:
        response = data_in.raw[:result]
        print(f"Response raw: {response}")
        print(f"Response text: {response.decode('utf-8', errors='replace')}")
    else:
        print("No response received")

except Exception as e:
    import traceback
    print(f"Error: {e}")
    traceback.print_exc()
