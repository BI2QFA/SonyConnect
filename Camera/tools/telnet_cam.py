# -*- coding: utf-8 -*-
"""Run telnet commands on the camera (argv: commands separated by ';;;')."""
import socket, sys, time

HOST, PORT = "192.168.31.25", 23

def strip_iac(buf):
    out = bytearray()
    i = 0
    while i < len(buf):
        if buf[i] == 0xFF and i + 2 < len(buf):
            i += 3
            continue
        out.append(buf[i]); i += 1
    return bytes(out)

def main():
    cmds = [c.encode() + b"\n" for c in sys.argv[1:]]
    s = socket.create_connection((HOST, PORT), timeout=10)
    s.settimeout(0.7)
    out = bytearray()

    def drain(seconds):
        end = time.time() + seconds
        while time.time() < end:
            try:
                b = s.recv(65536)
                if not b:
                    break
                out.extend(b)
                end = max(end, time.time() + 0.6)
            except socket.timeout:
                pass

    drain(2.0)
    for cmd in cmds:
        s.sendall(cmd)
        drain(8 if (b"logcat" in cmd or b"am start" in cmd or b"dumpsys" in cmd) else 2.5)
    try:
        s.sendall(b"exit\n")
    except OSError:
        pass
    s.close()
    sys.stdout.buffer.write(strip_iac(bytes(out)))
    sys.stdout.buffer.flush()

if __name__ == "__main__":
    main()
