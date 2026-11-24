"""Exercise the real backend and interactive Bash over the APK's frame format."""
import json
import os
import select
import struct
import subprocess
import sys
import tempfile
import time


class Terminal:
    def __init__(self, executable, home):
        self.process = subprocess.Popen(
            [executable], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            env={**os.environ, "HOME": home, "LANG": "C.UTF-8"}, bufsize=0,
        )
        self.input = self.process.stdin
        self.output = self.process.stdout
        self.buffer = bytearray()

    def send(self, data, kind=1):
        self.input.write(struct.pack(">IB", len(data) + 1, kind) + data)

    def event(self, event_type, **fields):
        self.send(json.dumps({"type": event_type, **fields}).encode(), 0)

    def receive(self, deadline):
        def take(length):
            while len(self.buffer) < length:
                remaining = deadline - time.monotonic()
                assert remaining > 0 and select.select([self.output], [], [], remaining)[0], "frame timeout"
                chunk = os.read(self.output.fileno(), 16384)
                assert chunk, f"unexpected EOF ({self.process.poll()})"
                self.buffer.extend(chunk)
            data = bytes(self.buffer[:length])
            del self.buffer[:length]
            return data

        size = struct.unpack(">I", take(4))[0]
        body = take(size)
        return json.loads(body[1:]) if body[0] == 0 else None

    def until(self, event_type):
        deadline = time.monotonic() + 15
        events = []
        while True:
            event = self.receive(deadline)
            if event:
                events.append(event)
                if event["type"] == event_type:
                    return events

    def command(self, text, expected_code=0):
        self.send(text.encode() + b"\r")
        events = self.until("prompt")
        assert [e["code"] for e in events if e["type"] == "finished"] == [expected_code], events
        assert len([e for e in events if e["type"] == "started"]) == 1, events
        return "".join(e["text"] for e in events if e["type"] == "output")

    def close(self):
        if self.process.poll() is None:
            self.event("close")
            self.input.close()
            self.process.wait(timeout=5)
        self.output.close()


def main(executable):
    with tempfile.TemporaryDirectory() as home:
        first = Terminal(executable, home)
        second = Terminal(executable, home)
        try:
            for terminal in (first, second):
                assert terminal.until("hello") == [{"type": "hello", "version": 2}]
                assert terminal.until("prompt")[-1]["directory"] == home
            assert first.command("printf '你好\\n' | cat") == "你好\n"
            first.command("false", 1)
            first.command("mkdir -p nested && cd nested && export WARP_TEST=one")
            assert first.command("printf '%s' \"$WARP_TEST\"") == "one"
            assert second.command("printf '%s' \"$WARP_TEST\"") == ""
            assert second.command("pwd").strip() == home
            first.event("resize", rows=40, columns=120)
            assert first.command("stty size").strip() == "40 120"
            first.send(b"printf 'Progress 0%%'; sleep 0.1; printf '\\r\\033[KProgress 50%%'; "
                       b"sleep 0.1; printf '\\r\\033[KProgress 100%%\\n'\r")
            progress = []
            for event in first.until("prompt"):
                if event["type"] == "output":
                    if progress and progress[-1][0] == "text":
                        progress[-1] = ("text", progress[-1][1] + event["text"])
                    else:
                        progress.append(("text", event["text"]))
                elif event["type"] == "line_edit":
                    progress.append((event["operation"], event["column"]))
            start = progress.index(("text", "Progress 0%"))
            assert progress[start:] == [
                ("text", "Progress 0%"), ("column", 0), ("clear_right", None),
                ("text", "Progress 50%"), ("column", 0), ("clear_right", None),
                ("text", "Progress 100%"), ("column", 0), ("text", "\n"),
            ], progress
            # APT draws on the last screen row while dpkg continues writing above it.
            first.send(b"printf '\\n\\0337\\033[0;39r\\0338\\033[1AUnpacking '; "
                       b"printf '\\0337\\033[4'; sleep 0.1; "
                       b"printf '0;0f\\033[42m\\033[30mProgress: [ 33%%]\\033[49m\\033[39m [\\342\\226\\210 ]\\0338'; "
                       b"sleep 0.15; printf 'test package\\nSetting up '; "
                       b"printf '\\0337\\033[40;0f\\033[42m\\033[30mProgress: [ 67%%]\\033[49m\\033[39m [\\342\\226\\210\\342\\226\\210]\\0338'; "
                       b"sleep 0.15; printf 'test package\\n\\n\\0337\\033[0;40r\\0338\\033[1A\\033JDone\\n'\r")
            events = first.until("prompt")
            transcript = "".join(e["text"] for e in events if e["type"] == "output")
            assert transcript == "\nUnpacking test package\nSetting up test package\n\nDone\n", transcript
            assert [e["text"] for e in events if e["type"] == "status_line"] == [
                "Progress: [ 33%] [█ ]", "Progress: [ 67%] [██]", None,
            ], events
            assert [e["code"] for e in events if e["type"] == "finished"] == [0], events
            first.send(b"sleep 30\r")
            first.until("started")
            time.sleep(0.15)
            first.send(b"\x03")
            events = first.until("prompt")
            assert [e["code"] for e in events if e["type"] == "finished"] == [130], events
            first.send(b"printf '\\033[?1049h\\033[?1049l'\r")
            events = first.until("prompt")
            assert [e["alternate"] for e in events if e["type"] == "screen"] == [True, False], events
            first.send(b"printf '\\033[?2031h\\033[>0q\\033[6n\\033[?2026$p\\033[?u'; "
                       b"printf '\\033_Gi=31337,s=1,v=1,a=q,t=d,f=24;AAAA'; sleep 0.1; "
                       b"printf '\\033\\\\\\033]8;;https://example.invalid\\033\\\\link"
                       b"\\033]8;;\\033\\\\\\n\\033[?1049h\\033[?1049l'\r")
            events = first.until("prompt")
            assert [e["alternate"] for e in events if e["type"] == "screen"] == [True, False], events
            assert first.command("printf 'BASH_STILL_READY\\n'") == "BASH_STILL_READY\n"
        finally:
            first.close()
            second.close()
    print("Bash PTY, exit codes, Ctrl+C, resize, progress, OpenCode startup probes and independent tabs passed.")


if __name__ == "__main__":
    main(sys.argv[1])
