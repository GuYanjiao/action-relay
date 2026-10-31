"""Send an owned watch key through local USB, without command-line key/log capture.

Requires pyserial. Open the selected watch channel first. This authenticates the
companion protocol only; it never resets a binding or controls the camera.
"""
import argparse
import pathlib
import re
import sys
import time

import serial


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", required=True)
    parser.add_argument("--key-file", type=pathlib.Path, required=True)
    args = parser.parse_args()
    key = bytearray(args.key_file.read_bytes().strip())
    if len(key) != 32 or any(value not in b"0123456789abcdefABCDEF" for value in key):
        key[:] = b"\0" * len(key)
        print("Expected a local file containing exactly one 32-character hex key.", file=sys.stderr)
        return 2
    command = bytearray(b"watch auth ")
    command.extend(key)
    command.extend(b"\n")
    key[:] = b"\0" * len(key)
    try:
        with serial.Serial(args.port, 115200, timeout=0.2) as port:
            # Discard old boot/status text. Never save or print the raw serial stream.
            port.reset_input_buffer()
            port.write(command)
            port.flush()
            command[:] = b"\0" * len(command)
            print("Local USB authentication requested; awaiting device confirmation.")
            deadline = time.monotonic() + 40
            while time.monotonic() < deadline:
                line = port.readline().decode("utf-8", errors="replace")
                if "S4 authentication confirmed by device" in line:
                    print("Device explicitly confirmed companion authentication.")
                    return 0
                if "S4 authentication failed" in line or "Unable to begin S4 authentication" in line:
                    print("Authentication failed; no automatic retry was sent.", file=sys.stderr)
                    return 1
                if "Watch diagnostic command result: -" in line:
                    match = re.search(r"result: (-?\d+)", line)
                    print("Board rejected authentication request (code " + (match[1] if match else "unknown") + ").", file=sys.stderr)
                    return 1
            print("No confirmation observed; outcome remains unknown. No retry was sent.", file=sys.stderr)
            return 3
    except serial.SerialException:
        print("USB communication failed; no confirmation can be claimed.", file=sys.stderr)
        return 4
    finally:
        command[:] = b"\0" * len(command)


if __name__ == "__main__":
    sys.exit(main())
