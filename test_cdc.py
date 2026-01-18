#!/usr/bin/env python3
"""Test Flipper Zero CDC communication via raw USB"""
import sys
import os
import struct
import fcntl
import time
import ctypes

if len(sys.argv) < 2:
    print("Usage: test_cdc.py <fd>")
    sys.exit(1)

fd = int(sys.argv[1])
print(f"USB FD: {fd}")

# ioctl numbers
USBDEVFS_CLAIMINTERFACE = 0x8004550f
USBDEVFS_RELEASEINTERFACE = 0x80045510
USBDEVFS_BULK = 0xc0185502
USBDEVFS_CONTROL = 0xc0185500

# CDC requests
SET_LINE_CODING = 0x20
SET_CONTROL_LINE_STATE = 0x22

class usbdevfs_ctrltransfer(ctypes.Structure):
    _fields_ = [
        ("bRequestType", ctypes.c_ubyte),
        ("bRequest", ctypes.c_ubyte),
        ("wValue", ctypes.c_ushort),
        ("wIndex", ctypes.c_ushort),
        ("wLength", ctypes.c_ushort),
        ("timeout", ctypes.c_uint),
        ("data", ctypes.c_void_p)
    ]

class usbdevfs_bulktransfer(ctypes.Structure):
    _fields_ = [
        ("ep", ctypes.c_uint),
        ("len", ctypes.c_uint),
        ("timeout", ctypes.c_uint),
        ("data", ctypes.c_void_p)
    ]

def claim_interface(fd, iface):
    try:
        fcntl.ioctl(fd, USBDEVFS_CLAIMINTERFACE, struct.pack('I', iface))
        return True
    except Exception as e:
        print(f"  Claim iface {iface}: {e}")
        return False

def control_transfer(fd, reqType, req, value, index, data=None, timeout=1000):
    if data:
        buf = ctypes.create_string_buffer(bytes(data))
        data_ptr = ctypes.addressof(buf)
        length = len(data)
    else:
        data_ptr = 0
        length = 0

    ctrl = usbdevfs_ctrltransfer()
    ctrl.bRequestType = reqType
    ctrl.bRequest = req
    ctrl.wValue = value
    ctrl.wIndex = index
    ctrl.wLength = length
    ctrl.timeout = timeout
    ctrl.data = data_ptr

    try:
        result = fcntl.ioctl(fd, USBDEVFS_CONTROL, ctrl)
        return result
    except Exception as e:
        print(f"  Control transfer error: {e}")
        return -1

def bulk_write(fd, ep, data, timeout=2000):
    buf = ctypes.create_string_buffer(data)
    bulk = usbdevfs_bulktransfer()
    bulk.ep = ep
    bulk.len = len(data)
    bulk.timeout = timeout
    bulk.data = ctypes.addressof(buf)

    try:
        result = fcntl.ioctl(fd, USBDEVFS_BULK, bulk)
        return result
    except Exception as e:
        print(f"  Bulk write error: {e}")
        return -1

def bulk_read(fd, ep, size, timeout=2000):
    buf = ctypes.create_string_buffer(size)
    bulk = usbdevfs_bulktransfer()
    bulk.ep = ep
    bulk.len = size
    bulk.timeout = timeout
    bulk.data = ctypes.addressof(buf)

    try:
        result = fcntl.ioctl(fd, USBDEVFS_BULK, bulk)
        if result > 0:
            return buf.raw[:result]
        return None
    except Exception as e:
        print(f"  Bulk read error: {e}")
        return None

# Flipper Zero CDC endpoints
EP_OUT = 0x01  # Bulk OUT
EP_IN = 0x82   # Bulk IN

print("\n=== Claiming interfaces ===")
# Interface 0 = CDC Control
# Interface 1 = CDC Data
claim_interface(fd, 0)
time.sleep(0.1)
claim_interface(fd, 1)
time.sleep(0.1)

print("\n=== Setting line coding (115200 8N1) ===")
# 115200 = 0x1C200
line_coding = bytes([0x00, 0xC2, 0x01, 0x00, 0x00, 0x00, 0x08])
result = control_transfer(fd, 0x21, SET_LINE_CODING, 0, 0, line_coding)
print(f"SET_LINE_CODING: {result}")

print("\n=== Setting control line state (DTR=0 RTS=0) ===")
result = control_transfer(fd, 0x21, SET_CONTROL_LINE_STATE, 0x00, 0)
print(f"SET_CONTROL_LINE_STATE: {result}")

time.sleep(0.3)

print("\n=== Draining buffer ===")
for i in range(5):
    data = bulk_read(fd, EP_IN, 512, 100)
    if data:
        print(f"  Drained {len(data)} bytes")
    else:
        break

print("\n=== Sending test command '?' ===")
cmd = b"?\r\n"
result = bulk_write(fd, EP_OUT, cmd)
print(f"Write result: {result}")

if result > 0:
    time.sleep(0.3)
    print("\n=== Reading response ===")
    response = b""
    for i in range(10):
        data = bulk_read(fd, EP_IN, 512, 300)
        if data:
            response += data
            print(f"  Read {len(data)} bytes")
            if b">:" in response:
                break
        else:
            break
        time.sleep(0.1)

    if response:
        print(f"\nResponse:\n{response.decode('utf-8', errors='replace')}")
    else:
        print("No response received")
else:
    print("WRITE FAILED!")

print("\n=== Done ===")
